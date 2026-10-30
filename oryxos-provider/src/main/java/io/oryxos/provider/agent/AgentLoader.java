package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.ProfileValidator;
import io.oryxos.provider.ProfileLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns one agent directory into an {@link AgentDefinition}. The frontmatter uses the fields of the
 * existing Profile, validated by the same {@link ProfileLoader#parse}, so there is exactly one
 * Profile schema. Everything invalid is reported with the file, the field and what to change;
 * nothing is skipped quietly. Reading never runs anything: scripts and reference files are listed
 * by name only.
 */
public final class AgentLoader {
  public static final String FILE_NAME = "AGENT.md";
  public static final String MEMORY_FILE = "MEMORY.md";
  static final int MAX_RESOURCES = 100;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Set<String> KEYS =
      Set.of(
          "name",
          "description",
          "identity",
          "provider",
          "model",
          "temperature",
          "tools",
          "skills",
          "mcp_servers",
          "channels",
          "notify_channels",
          "schedules",
          "bootstrap",
          "settings");
  private static final List<String> TEXT_LISTS = List.of("tools", "skills", "mcp_servers");
  private static final List<String> MAP_LISTS = List.of("channels", "notify_channels", "schedules");
  private static final List<String> MAPS = List.of("identity", "bootstrap", "settings");

  private final ProfileLoader profiles;
  private final Supplier<Set<String>> knownTools;
  private final List<ProfileValidator> validators;

  /**
   * @param knownTools the names of the installed tools, asked at every load; null skips the check
   * @param validators extra checks run on the finished Profile (for example the schedule parser)
   */
  public AgentLoader(
      ProfileLoader profiles, Supplier<Set<String>> knownTools, List<ProfileValidator> validators) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.knownTools = knownTools;
    this.validators = List.copyOf(validators);
  }

  /** Reads and validates {@code <agentDir>/AGENT.md}. */
  public AgentDefinition load(Path agentDir) {
    String dirName = nameOf(agentDir);
    Path file = agentDir.resolve(FILE_NAME);
    if (Files.isSymbolicLink(file)) {
      throw failure(dirName, FILE_NAME + " must be a regular file, not a link");
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw failure(dirName, FILE_NAME + " not found (an agent directory needs an AGENT.md)");
    }
    return parse(dirName, readText(dirName, file), agentDir, listResources(agentDir));
  }

  /** The Profile an agent directory stands for. */
  public AgentProfile deriveProfile(Path agentDir) {
    return load(agentDir).profile();
  }

  /**
   * Validates AGENT.md text without touching the disk (used for drafts before they are saved).
   * {@code resources} are the names of the files that will sit beside it.
   */
  public AgentDefinition parse(
      String dirName, String agentMd, Path directory, List<String> resources) {
    if (!NAME.matcher(dirName).matches()) {
      throw failure(dirName, "the agent name must match [A-Za-z0-9_-]{1,64}");
    }
    try {
      AgentMd.Parts parts = AgentMd.parse(agentMd);
      Map<String, Object> source = normalise(parts.frontmatter(), dirName, parts.body());
      AgentProfile profile = buildProfile(source, directory, parts.body(), resources);
      for (ProfileValidator validator : validators) {
        validator.validate(profile);
      }
      return new AgentDefinition(
          dirName, directory, profile, parts.body(), resources, hash(agentMd, resources));
    } catch (AgentDefinitionException problem) {
      throw failure(dirName, problem.getMessage());
    }
  }

  /** Identifies this exact text plus file list, so an unchanged directory is recognised. */
  static String hash(String agentMd, List<String> resources) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      digest.update(agentMd.getBytes(StandardCharsets.UTF_8));
      for (String resource : resources) {
        digest.update((byte) 0);
        digest.update(resource.getBytes(StandardCharsets.UTF_8));
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }

  private AgentProfile buildProfile(
      Map<String, Object> source, Path directory, String body, List<String> resources) {
    Map<String, Object> withPrompt = new LinkedHashMap<>(source);
    Map<String, Object> identity = new LinkedHashMap<>();
    if (source.get("identity") instanceof Map<?, ?> given) {
      given.forEach((key, value) -> identity.put(String.valueOf(key), value));
    }
    identity.put("prompt", prompt(body, directory, resources));
    withPrompt.put("identity", identity);
    try {
      return profiles.parse(withPrompt);
    } catch (RuntimeException invalid) {
      // Includes SandboxConfigException and a number that is not a number.
      throw new AgentDefinitionException(describe(invalid));
    }
  }

  private static String describe(RuntimeException invalid) {
    String message = invalid.getMessage();
    if (message == null) {
      return invalid.getClass().getSimpleName();
    }
    return message.length() > 300 ? message.substring(0, 300) : message;
  }

  /** The Markdown body, then the names (only) of the files the agent may read when it needs. */
  static String prompt(String body, Path directory, List<String> resources) {
    if (resources.isEmpty()) {
      return body;
    }
    StringBuilder out = new StringBuilder(body);
    out.append("\n\n## Agent files (names only; not loaded)\n");
    out.append("Directory: ").append(directory.toString().replace('\\', '/')).append('\n');
    out.append("Read one of these with the file tools only when the task needs it.\n");
    resources.forEach(name -> out.append("- ").append(name).append('\n'));
    return out.toString().stripTrailing();
  }

  private Map<String, Object> normalise(Map<String, Object> front, String dirName, String body) {
    Set<String> unknown = new TreeSet<>(front.keySet());
    unknown.removeAll(KEYS);
    if (!unknown.isEmpty()) {
      throw new AgentDefinitionException(
          "unknown frontmatter field(s) " + unknown + " (allowed: " + new TreeSet<>(KEYS) + ")");
    }
    Map<String, Object> result = new LinkedHashMap<>(front);
    Object name = front.getOrDefault("name", dirName);
    if (!(name instanceof String given) || !given.equals(dirName)) {
      throw new AgentDefinitionException(
          "name must be the directory name '" + dirName + "' but was '" + name + "'");
    }
    result.put("name", dirName);
    if (!(front.get("description") instanceof String text) || text.isBlank()) {
      throw new AgentDefinitionException("description is required and must be text");
    }
    if (body.isBlank()) {
      throw new AgentDefinitionException(
          "there are no instructions: write the business instructions below the frontmatter");
    }
    if (front.get("identity") instanceof Map<?, ?> identity && identity.containsKey("prompt")) {
      throw new AgentDefinitionException(
          "identity.prompt conflicts with the Markdown body, which is the prompt; remove one");
    }
    for (String key : TEXT_LISTS) {
      requireTextList(front, key);
    }
    for (String key : MAP_LISTS) {
      requireMapList(front, key);
    }
    for (String key : MAPS) {
      if (front.get(key) != null && !(front.get(key) instanceof Map)) {
        throw new AgentDefinitionException(key + " must be a mapping");
      }
    }
    checkTools(front.get("tools"));
    result.remove("model");
    result.remove("temperature");
    result.put("provider", provider(front));
    return result;
  }

  /** Accepts the Profile's nested form, or the shorthand {@code provider: name} plus model. */
  private static Map<String, Object> provider(Map<String, Object> front) {
    Object provider = front.get("provider");
    Map<String, Object> result = new LinkedHashMap<>();
    if (provider instanceof String name && !name.isBlank()) {
      if (!(front.get("model") instanceof String model) || model.isBlank()) {
        throw new AgentDefinitionException(
            "model is required when provider is given as a name (provider: name + model: id)");
      }
      result.put("name", name);
      result.put("model", model);
      if (front.get("temperature") != null) {
        result.put("temperature", front.get("temperature"));
      }
      return result;
    }
    if (provider instanceof Map<?, ?> nested) {
      if (front.containsKey("model") || front.containsKey("temperature")) {
        throw new AgentDefinitionException(
            "give model/temperature inside provider or at the top level, not both");
      }
      nested.forEach((key, value) -> result.put(String.valueOf(key), value));
      return result;
    }
    throw new AgentDefinitionException(
        "provider is required: a name (with model) or a mapping {name, model, temperature}");
  }

  private void checkTools(Object declared) {
    if (!(declared instanceof List<?> tools)) {
      return;
    }
    Set<String> seen = new TreeSet<>();
    Set<String> installed = knownTools == null ? null : knownTools.get();
    for (Object tool : tools) {
      String name = String.valueOf(tool);
      if (!seen.add(name)) {
        throw new AgentDefinitionException("tool '" + name + "' is listed twice");
      }
      if (installed != null && !installed.contains(name)) {
        throw new AgentDefinitionException(
            "tool '"
                + name
                + "' is not registered (registered tools: "
                + new TreeSet<>(installed)
                + ")");
      }
    }
  }

  private static void requireTextList(Map<String, Object> front, String key) {
    Object value = front.get(key);
    if (value == null) {
      return;
    }
    if (!(value instanceof List<?> items)) {
      throw new AgentDefinitionException(key + " must be a list of text");
    }
    for (Object item : items) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new AgentDefinitionException(key + " must contain only non-empty text entries");
      }
    }
  }

  private static void requireMapList(Map<String, Object> front, String key) {
    Object value = front.get(key);
    if (value == null) {
      return;
    }
    if (!(value instanceof List<?> items)) {
      throw new AgentDefinitionException(key + " must be a list of mappings");
    }
    for (Object item : items) {
      if (!(item instanceof Map)) {
        throw new AgentDefinitionException(key + " must contain only mappings");
      }
    }
  }

  private static String readText(String dirName, Path file) {
    try {
      if (Files.size(file) > (long) AgentMd.MAX_CHARS * 4) {
        throw failure(dirName, FILE_NAME + " is far too large");
      }
      byte[] bytes = Files.readAllBytes(file);
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw failure(dirName, FILE_NAME + " is not valid UTF-8");
    } catch (IOException e) {
      throw failure(dirName, FILE_NAME + " cannot be read (" + e.getClass().getSimpleName() + ")");
    }
  }

  /** Names of the files beside AGENT.md (never their content), sorted, links not followed. */
  public static List<String> listResources(Path agentDir) {
    List<String> names = new ArrayList<>();
    try (DirectoryStream<Path> top = Files.newDirectoryStream(agentDir)) {
      for (Path entry : top) {
        String name = nameOf(entry);
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
            && !name.startsWith(".")
            && !FILE_NAME.equals(name)
            && !MEMORY_FILE.equals(name)) {
          names.add(name);
        }
      }
    } catch (IOException e) {
      return List.of();
    }
    for (String sub : List.of("skills", "scripts")) {
      Path dir = agentDir.resolve(sub);
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(dir, 3)) {
        walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            .filter(p -> WorkspacePaths.plain(agentDir, p))
            .filter(p -> !p.toString().contains("__pycache__"))
            .map(p -> agentDir.relativize(p).toString().replace('\\', '/'))
            .forEach(names::add);
      } catch (IOException | UncheckedIOException e) {
        continue; // a directory that cannot be read simply contributes no names
      }
    }
    return names.stream().sorted().limit(MAX_RESOURCES).toList();
  }

  /** The last path segment; a path without one (a filesystem root) is not an agent directory. */
  static String nameOf(Path agentDir) {
    Path name = agentDir.getFileName();
    if (name == null) {
      throw new AgentDefinitionException("agent directory has no name: " + agentDir);
    }
    return name.toString();
  }

  private static AgentDefinitionException failure(String dirName, String message) {
    return new AgentDefinitionException("agents/" + dirName + "/AGENT.md: " + message);
  }
}
