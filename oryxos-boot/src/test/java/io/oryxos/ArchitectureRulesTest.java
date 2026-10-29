package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The module boundaries from docs/architecture/module-boundaries.md, as executable checks over the
 * source tree. A violation names the file, so a drift (say, a controller importing a JPA class) is
 * caught by the build instead of by the next review. Plain text and XML scanning: no extra library.
 */
class ArchitectureRulesTest {
  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Pattern IMPORT =
      Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

  /** Allowed compile-scope dependencies between the nine modules. */
  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "core", Set.of(),
          "provider", Set.of("core"),
          "tool", Set.of("core"),
          "memory", Set.of("core"),
          "storage", Set.of("core", "provider"),
          "scheduler", Set.of("core"),
          "cli", Set.of("core", "tool"),
          "web", Set.of("core"),
          "boot",
              Set.of("core", "provider", "tool", "memory", "storage", "scheduler", "cli", "web"));

  private static List<Path> mainSources(String module) throws IOException {
    Path dir = ROOT.resolve("oryxos-" + module).resolve("src/main/java");
    try (Stream<Path> files = Files.walk(dir)) {
      return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static List<String> importsOf(Path file) throws IOException {
    Matcher m = IMPORT.matcher(Files.readString(file));
    List<String> result = new ArrayList<>();
    while (m.find()) {
      result.add(m.group(1));
    }
    return result;
  }

  /** Files of {@code module} that import something starting with one of the prefixes. */
  private static List<String> offenders(String module, String... prefixes) throws IOException {
    List<String> bad = new ArrayList<>();
    for (Path file : mainSources(module)) {
      for (String imported : importsOf(file)) {
        for (String prefix : prefixes) {
          if (imported.startsWith(prefix)) {
            bad.add(file.getFileName() + " imports " + imported);
          }
        }
      }
    }
    return bad;
  }

  @Test
  void theNineModulesAreExactlyTheCourseModules() throws IOException {
    try (Stream<Path> dirs = Files.list(ROOT)) {
      Set<String> modules =
          dirs.filter(Files::isDirectory)
              .map(p -> p.getFileName().toString())
              .filter(n -> n.startsWith("oryxos-"))
              .map(n -> n.substring("oryxos-".length()))
              .collect(Collectors.toCollection(TreeSet::new));
      assertEquals(new TreeSet<>(ALLOWED.keySet()), modules);
    }
  }

  @Test
  void compileScopeDependenciesFollowTheAllowedGraph() throws Exception {
    for (Map.Entry<String, Set<String>> module : ALLOWED.entrySet()) {
      Path pom = ROOT.resolve("oryxos-" + module.getKey()).resolve("pom.xml");
      var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
      NodeList dependencies = doc.getElementsByTagName("dependency");
      Set<String> actual = new TreeSet<>();
      for (int i = 0; i < dependencies.getLength(); i++) {
        Element dependency = (Element) dependencies.item(i);
        String group = text(dependency, "groupId");
        String artifact = text(dependency, "artifactId");
        String scope = text(dependency, "scope");
        if ("io.oryxos".equals(group) && !"test".equals(scope)) {
          actual.add(artifact.substring("oryxos-".length()));
        }
      }
      assertEquals(
          new TreeSet<>(module.getValue()),
          actual,
          "oryxos-" + module.getKey() + " compile-scope module dependencies");
    }
  }

  private static String text(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
  }

  @Test
  void coreImportsNothingButTheJdkAndItself() throws IOException {
    List<String> bad = new ArrayList<>();
    for (Path file : mainSources("core")) {
      for (String imported : importsOf(file)) {
        if (!imported.startsWith("java.") && !imported.startsWith("io.oryxos.core")) {
          bad.add(file.getFileName() + " imports " + imported);
        }
      }
    }
    assertTrue(bad.isEmpty(), "core must not depend on frameworks or infrastructure: " + bad);
  }

  @Test
  void persistenceApisAreUsedInStorageOnlyExceptTheDocumentedMemoryJdbcStore() throws IOException {
    List<String> bad = new ArrayList<>();
    for (String module : ALLOWED.keySet()) {
      if ("storage".equals(module)) {
        continue;
      }
      bad.addAll(
          offenders(
              module,
              "jakarta.persistence",
              "org.hibernate",
              "org.springframework.data",
              "org.springframework.jdbc",
              "org.sqlite"));
      for (Path file : mainSources(module)) {
        for (String imported : importsOf(file)) {
          boolean sql = imported.startsWith("java.sql") || imported.startsWith("javax.sql");
          boolean documentedException =
              file.getFileName().toString().equals("SqliteMemoryStore.java")
                  || file.getFileName().toString().equals("MemoryConfiguration.java");
          if (sql && !documentedException) {
            bad.add(file.getFileName() + " imports " + imported);
          }
        }
      }
    }
    assertTrue(bad.isEmpty(), "database access outside oryxos-storage: " + bad);
  }

  @Test
  void restControllersNeverReachIntoExecutionOrInfrastructureModules() throws IOException {
    List<String> bad =
        offenders(
            "web",
            "io.oryxos.storage",
            "io.oryxos.provider",
            "io.oryxos.tool",
            "io.oryxos.memory",
            "io.oryxos.scheduler",
            "io.oryxos.core.agent.ReActLoop",
            "io.oryxos.core.agent.AgentToolExecutor",
            "io.oryxos.core.agent.ModelGateway");
    assertTrue(bad.isEmpty(), "web talks to services through core ports only: " + bad);
  }

  @Test
  void schedulerAndProviderCannotRunToolsOrTheLoopDirectly() throws IOException {
    List<String> scheduler =
        offenders(
            "scheduler",
            "io.oryxos.tool",
            "io.oryxos.provider",
            "io.oryxos.core.agent.ReActLoop",
            "io.oryxos.core.agent.AgentToolExecutor");
    assertTrue(scheduler.isEmpty(), "the scheduler must go through AgentService: " + scheduler);
    List<String> provider =
        offenders("provider", "io.oryxos.tool", "io.oryxos.core.agent.AgentToolExecutor");
    assertTrue(
        provider.isEmpty(), "providers advertise tools, they never execute them: " + provider);
  }

  @Test
  void thereIsOneToolRegistryOneSessionStoreAndOneToolCallAuditWriter() throws IOException {
    List<String> registries = new ArrayList<>();
    List<String> stores = new ArrayList<>();
    List<String> audits = new ArrayList<>();
    for (String module : ALLOWED.keySet()) {
      for (Path file : mainSources(module)) {
        String source = Files.readString(file);
        if (source.contains("new ToolRegistry(")) {
          registries.add(file.getFileName().toString());
        }
        if (Pattern.compile("implements\\s+[^{]*\\bAgentSessionStore\\b").matcher(source).find()) {
          stores.add(file.getFileName().toString());
        }
        if (Pattern.compile("implements\\s+[^{]*\\bToolCallAudit\\b").matcher(source).find()) {
          audits.add(file.getFileName().toString());
        }
      }
    }
    assertEquals(List.of("ToolConfiguration.java"), registries);
    assertEquals(List.of("JpaAgentSessionStore.java"), stores);
    assertEquals(List.of("JpaToolCallAudit.java"), audits);
  }

  @Test
  void everyToolExecutorInProductionCodeIsWrappedByTheAudit() throws IOException {
    Pattern bare = Pattern.compile("new\\s+(RegistryToolExecutor|DemoToolExecutor)\\(");
    List<String> unaudited = new ArrayList<>();
    for (String module : ALLOWED.keySet()) {
      for (Path file : mainSources(module)) {
        String source = Files.readString(file);
        Matcher m = bare.matcher(source);
        while (m.find()) {
          String before = source.substring(Math.max(0, m.start() - 40), m.start());
          if (!before.contains("new AuditedToolExecutor(")) {
            unaudited.add(file.getFileName() + " creates " + m.group(1) + " without audit");
          }
        }
      }
    }
    assertTrue(unaudited.isEmpty(), "tool execution must be audited: " + unaudited);
  }
}
