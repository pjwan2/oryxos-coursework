package io.oryxos.provider.agent;

import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.profile.AgentConflictException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentFile;
import io.oryxos.core.profile.AgentManagement;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.AgentView;
import io.oryxos.core.profile.ArchiveResult;
import io.oryxos.core.schedule.ProfileScheduleSync;
import io.oryxos.provider.ProfileRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way an agent directory becomes a live agent, whatever brought it here: the startup scan,
 * a copied directory noticed by the watcher, or the REST API. {@link #register} always runs the
 * same steps: read and validate the directory, check the name is free (or already ours), swap the
 * registry, tell the scheduler; a failure at any step puts the previous state back, so there is no
 * half-registered agent. The REST operations (create, update, archive, save files) are built on it
 * and also put the files back when they fail.
 */
public class AgentLifecycleService implements AgentManagement {
  private static final Logger LOG = LoggerFactory.getLogger(AgentLifecycleService.class);
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final int MAX_DESCRIPTION = 300;
  static final int MAX_FILE_BYTES = 256 * 1024;
  static final int MAX_FILES_PER_SAVE = 20;

  /** Where archives go, the provider and model a new agent starts with, and the clock for names. */
  public record Options(Path archiveRoot, String provider, String model, Clock clock) {
    public Options {
      Objects.requireNonNull(archiveRoot, "archiveRoot");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(clock, "clock");
    }
  }

  private final ProfileRegistry registry;
  private final AgentDirectoryScanner scanner;
  private final AgentLoader loader;
  private final ProfileScheduleSync scheduler;
  private final Options options;
  private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();
  private final Map<String, String> problems = new ConcurrentHashMap<>();

  public AgentLifecycleService(
      ProfileRegistry registry,
      AgentDirectoryScanner scanner,
      AgentLoader loader,
      ProfileScheduleSync scheduler,
      AgentStartup.Report startup) {
    this(
        registry,
        scanner,
        loader,
        scheduler,
        startup,
        new Options(
            scanner.root().resolveSibling("archive"), "mock", "mock-echo", Clock.systemUTC()));
  }

  public AgentLifecycleService(
      ProfileRegistry registry,
      AgentDirectoryScanner scanner,
      AgentLoader loader,
      ProfileScheduleSync scheduler,
      AgentStartup.Report startup,
      Options options) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.scanner = Objects.requireNonNull(scanner, "scanner");
    this.loader = Objects.requireNonNull(loader, "loader");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.options = Objects.requireNonNull(options, "options");
    agents.putAll(startup.agents());
    problems.putAll(startup.problems());
  }

  public Path root() {
    return scanner.root();
  }

  public AgentLoader loader() {
    return loader;
  }

  /** Directory of the named agent under the agents root (not checked for existence). */
  public Path directoryOf(String name) {
    return scanner.root().resolve(name);
  }

  // ---- registration (shared by startup, watcher and REST) --------------------------------

  /**
   * Registers the agent in {@code agentDir}, or reloads it when it is already ours. An existing
   * valid agent stays untouched if the new files are invalid. An unchanged directory is a no-op.
   */
  public synchronized AgentDefinition register(Path agentDir) {
    String name = AgentLoader.nameOf(agentDir);
    AgentDefinition definition;
    try {
      scanner.requireInside(agentDir);
      definition = loader.load(agentDir);
    } catch (AgentDefinitionException invalid) {
      problems.put(name, invalid.getMessage());
      throw invalid;
    }
    AgentProfile before = registry.exists(name) ? registry.get(name) : null;
    if (before != null && !agents.containsKey(name)) {
      String conflict = AgentStartup.conflictMessage(name);
      problems.put(name, conflict);
      throw new AgentConflictException(conflict);
    }
    AgentDefinition previous = agents.get(name);
    if (previous != null
        && previous.contentHash().equals(definition.contentHash())
        && definition.profile().equals(before)) {
      problems.remove(name);
      return previous;
    }
    apply(definition, before);
    agents.put(name, definition);
    problems.remove(name);
    LOG.info("Agent '{}' {}", name, before == null ? "registered" : "reloaded");
    return definition;
  }

  /** Re-reads the directory of an agent that is already registered from AGENT.md. */
  public synchronized AgentDefinition reload(String name) {
    requireOwned(name);
    return register(directoryOf(name));
  }

  /** Stops and forgets an AGENT.md agent; false if there was none. Files are not touched. */
  public synchronized boolean unregister(String name) {
    if (!agents.containsKey(name)) {
      if (registry.exists(name)) {
        throw legacyReadOnly(name);
      }
      return false;
    }
    AgentProfile before = registry.exists(name) ? registry.get(name) : null;
    registry.remove(name);
    try {
      scheduler.unregisterProfile(name);
    } catch (RuntimeException failure) {
      if (before != null) {
        registry.register(before);
      }
      throw failure;
    }
    agents.remove(name);
    problems.remove(name);
    LOG.info("Agent '{}' unregistered", name);
    return true;
  }

  /** Validates a directory exactly as register would, without registering anything. */
  public AgentDefinition validate(Path agentDir) {
    scanner.requireInside(agentDir);
    return loader.load(agentDir);
  }

  public Optional<AgentDefinition> definition(String name) {
    return Optional.ofNullable(agents.get(name));
  }

  /** True when the agent is registered but has no AGENT.md (a legacy, read-only profile). */
  public boolean isLegacy(String name) {
    return registry.exists(name) && !agents.containsKey(name);
  }

  /** Names of the registered AGENT.md agents. */
  public List<String> agentNames() {
    return agents.keySet().stream().sorted().toList();
  }

  @Override
  public Map<String, String> problems() {
    return Collections.unmodifiableMap(new TreeMap<>(problems));
  }

  public void recordProblem(String name, String message) {
    problems.put(name, message);
  }

  public void clearProblem(String name) {
    problems.remove(name);
  }

  /**
   * Brings the registry in line with the disk: registers new or changed directories, retires agents
   * whose directory is gone. A safety net for file events the watcher may have missed.
   */
  public synchronized void reconcile() {
    AgentDirectoryScanner.Result found = scanner.scan();
    for (AgentDefinition seen : found.agents()) {
      AgentDefinition known = agents.get(seen.name());
      if (known == null || !known.contentHash().equals(seen.contentHash())) {
        try {
          register(seen.directory());
        } catch (AgentDefinitionException | AgentConflictException refused) {
          LOG.warn("Agent '{}' not applied: {}", seen.name(), refused.getMessage());
        }
      }
    }
    for (Map.Entry<String, String> bad : found.problems().entrySet()) {
      problems.put(bad.getKey(), bad.getValue());
    }
    for (String name : new ArrayList<>(agents.keySet())) {
      if (!Files.isDirectory(directoryOf(name))) {
        unregister(name);
      }
    }
  }

  // ---- AgentManagement -------------------------------------------------------------------

  @Override
  public synchronized AgentView create(String name, String description) {
    requireValidName(name);
    String text = description == null ? "" : description.strip();
    if (text.isEmpty() || text.length() > MAX_DESCRIPTION) {
      throw new AgentDefinitionException(
          "description is required and must be at most " + MAX_DESCRIPTION + " characters");
    }
    if (registry.exists(name) || problems.containsKey(name) || Files.exists(directoryOf(name))) {
      throw new AgentConflictException("An agent or directory named '" + name + "' already exists");
    }
    Path dir = directoryOf(name);
    try {
      Files.createDirectories(scanner.root());
      Files.createDirectory(dir);
      writeAtomic(dir.resolve(AgentLoader.FILE_NAME), scaffold(name, text));
    } catch (IOException e) {
      deleteQuietly(dir);
      throw new IllegalStateException("The agent directory could not be created", e);
    }
    try {
      register(dir);
    } catch (RuntimeException failure) {
      deleteQuietly(dir); // we created it a moment ago; nothing of anyone else's is lost
      problems.remove(name);
      throw failure;
    }
    return get(name);
  }

  @Override
  public AgentView get(String name) {
    return view(registry.get(name));
  }

  @Override
  public List<AgentView> list() {
    return registry.list().stream().map(this::view).toList();
  }

  @Override
  public synchronized AgentView update(String name, String agentMd) {
    requireOwned(name);
    Objects.requireNonNull(agentMd, "agentMd");
    Path dir = directoryOf(name);
    // Nothing is written until the new text has passed the same validation as a saved agent.
    loader.parse(name, agentMd, dir, AgentLoader.listResources(dir));
    Path file = dir.resolve(AgentLoader.FILE_NAME);
    byte[] previous = readOrNull(file);
    try {
      writeAtomic(file, agentMd);
      register(dir);
    } catch (IOException e) {
      restore(file, previous);
      throw new IllegalStateException("AGENT.md could not be written", e);
    } catch (RuntimeException failure) {
      restore(file, previous);
      throw failure;
    }
    return get(name);
  }

  @Override
  public synchronized ArchiveResult archive(String name) {
    requireOwned(name);
    Path dir = directoryOf(name);
    Path target = archiveTarget(name);
    unregister(name);
    try {
      Files.createDirectories(options.archiveRoot());
      move(dir, target);
    } catch (IOException | RuntimeException failure) {
      LOG.error("Archiving '{}' failed ({}); registering it again", name, failure.toString());
      register(dir);
      throw new IllegalStateException("The agent could not be archived and was left as it was");
    }
    return new ArchiveResult(name, label(target));
  }

  @Override
  public synchronized AgentView saveFiles(String name, List<AgentFile> files) {
    requireOwned(name);
    Objects.requireNonNull(files, "files");
    if (files.isEmpty() || files.size() > MAX_FILES_PER_SAVE) {
      throw new AgentDefinitionException(
          "give between 1 and " + MAX_FILES_PER_SAVE + " files to save");
    }
    Path dir = directoryOf(name);
    Map<String, String> wanted = checkedFiles(name, files);
    List<String> resourcesAfter = new ArrayList<>(AgentLoader.listResources(dir));
    for (String path : wanted.keySet()) {
      if (!AgentLoader.FILE_NAME.equals(path) && !resourcesAfter.contains(path)) {
        resourcesAfter.add(path);
      }
    }
    resourcesAfter.sort(Comparator.naturalOrder());
    // The AGENT.md that will result must be valid before any file is touched.
    String agentMd =
        wanted.containsKey(AgentLoader.FILE_NAME)
            ? wanted.get(AgentLoader.FILE_NAME)
            : readText(dir.resolve(AgentLoader.FILE_NAME));
    loader.parse(name, agentMd, dir, resourcesAfter);

    Map<Path, byte[]> undo = new LinkedHashMap<>();
    List<Path> created = new ArrayList<>();
    try {
      for (Map.Entry<String, String> entry : wanted.entrySet()) {
        Path target = dir.resolve(entry.getKey());
        if (Files.exists(target)) {
          undo.put(target, Files.readAllBytes(target));
        } else {
          created.add(target);
        }
        Files.createDirectories(parentOf(target));
        writeAtomic(target, entry.getValue());
      }
      register(dir);
    } catch (IOException | RuntimeException failure) {
      undo.forEach(AgentLifecycleService::restore);
      created.forEach(AgentLifecycleService::deleteQuietly);
      if (failure instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("The files could not be written", failure);
    }
    return get(name);
  }

  /** Validated relative path to content; refuses links, odd names, big files and duplicates. */
  private Map<String, String> checkedFiles(String name, List<AgentFile> files) {
    Map<String, String> wanted = new TreeMap<>();
    for (AgentFile file : files) {
      List<String> segments = WorkspacePaths.segments(file.path());
      String relative = String.join("/", segments);
      String leaf = segments.getLast();
      WorkspacePaths.requireTextType(leaf);
      if (AgentLoader.MEMORY_FILE.equals(leaf) && segments.size() == 1) {
        throw WorkspacePaths.refused("MEMORY.md is written by the agent's memory tools only");
      }
      if (file.content().getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
        throw WorkspacePaths.refused("a file is larger than " + MAX_FILE_BYTES + " bytes");
      }
      if (wanted.put(relative, file.content()) != null) {
        throw new AgentDefinitionException("the path " + relative + " is listed twice");
      }
      WorkspacePaths.resolve(directoryOf(name), segments);
    }
    return wanted;
  }

  // ---- views -------------------------------------------------------------------------------

  private AgentView view(AgentProfile profile) {
    AgentDefinition definition = agents.get(profile.name());
    List<AgentView.Schedule> schedules = new ArrayList<>();
    for (Map<String, Object> entry : profile.schedules()) {
      schedules.add(
          new AgentView.Schedule(
              text(entry.get("id")),
              text(entry.get("cron")),
              text(entry.get("zone")),
              text(entry.get("message"))));
    }
    boolean fromDirectory = definition != null;
    return new AgentView(
        profile.name(),
        profile.description(),
        fromDirectory ? AgentView.ORIGIN_AGENT_MD : AgentView.ORIGIN_LEGACY,
        fromDirectory,
        profile.provider().name(),
        profile.provider().model(),
        profile.provider().temperature(),
        profile.tools(),
        profile.skills(),
        schedules,
        fromDirectory ? definition.resources() : List.of(),
        fromDirectory,
        fromDirectory ? definition.instructions() : null,
        problems.get(profile.name()));
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  // ---- internals ---------------------------------------------------------------------------

  private void requireOwned(String name) {
    if (agents.containsKey(name)) {
      return;
    }
    if (registry.exists(name)) {
      throw legacyReadOnly(name);
    }
    throw new ProfileNotFoundException(name);
  }

  private static AgentConflictException legacyReadOnly(String name) {
    return new AgentConflictException(
        "Agent '"
            + name
            + "' comes from a legacy profiles/*.yaml and is read-only; define it as agents/"
            + name
            + "/AGENT.md to manage it here");
  }

  private static void requireValidName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new AgentDefinitionException("the agent name must match [A-Za-z0-9_-]{1,64}");
    }
  }

  private String scaffold(String name, String description) {
    return "---\nname: "
        + name
        + "\ndescription: "
        + yamlText(description)
        + "\nprovider: {name: "
        + options.provider()
        + ", model: "
        + options.model()
        + ", temperature: 0.2}\ntools: []\n---\n# "
        + name
        + "\n\n"
        + description.replace('\n', ' ')
        + "\n\nWrite this agent's instructions here.\n";
  }

  /** A double-quoted YAML scalar, so no description can change the structure of the frontmatter. */
  private static String yamlText(String value) {
    return "\""
        + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
        + "\"";
  }

  private void apply(AgentDefinition definition, AgentProfile before) {
    AgentProfile profile = definition.profile();
    try {
      if (before == null) {
        registry.register(profile);
      } else {
        registry.replace(profile);
      }
      scheduler.registerProfile(profile);
    } catch (RuntimeException failure) {
      restoreRegistry(profile.name(), before);
      throw failure;
    }
  }

  private void restoreRegistry(String name, AgentProfile before) {
    try {
      if (before == null) {
        registry.remove(name);
        scheduler.unregisterProfile(name);
      } else {
        registry.replace(before);
        scheduler.registerProfile(before);
      }
    } catch (RuntimeException secondary) {
      LOG.error("Could not restore agent '{}' after a failure: {}", name, secondary.toString());
    }
  }

  private Path archiveTarget(String name) {
    String base = name + "-" + STAMP.format(options.clock().instant());
    Path target = options.archiveRoot().resolve(base);
    for (int n = 2; Files.exists(target); n++) {
      target = options.archiveRoot().resolve(base + "-" + n);
    }
    return target;
  }

  private String label(Path target) {
    Path leaf = options.archiveRoot().getFileName();
    return (leaf == null ? "archive" : leaf.toString()) + "/" + target.getFileName();
  }

  private static void move(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException notAtomic) {
      Files.move(from, to);
    }
  }

  private static Path parentOf(Path file) {
    Path parent = file.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("a file path needs a parent directory");
    }
    return parent;
  }

  /** Temp file in the same directory, then an atomic replace: a reader never sees half a file. */
  static void writeAtomic(Path target, String content) throws IOException {
    Path parent = parentOf(target);
    Path temp = Files.createTempFile(parent, ".tmp-", ".part");
    try {
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException notAtomic) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static byte[] readOrNull(Path file) {
    try {
      return Files.exists(file) ? Files.readAllBytes(file) : null;
    } catch (IOException e) {
      return null;
    }
  }

  private static String readText(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AgentDefinitionException("AGENT.md cannot be read");
    }
  }

  private static void restore(Path file, byte[] previous) {
    try {
      if (previous == null) {
        Files.deleteIfExists(file);
      } else {
        Files.write(file, previous);
      }
    } catch (IOException e) {
      LOG.error("Could not restore {}: {}", file.getFileName(), e.toString());
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (Stream<Path> walk = Files.walk(path)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      } else {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      LOG.warn("Could not clean up {}", path.getFileName());
    }
  }
}
