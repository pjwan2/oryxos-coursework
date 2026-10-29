package io.oryxos.provider;

import io.oryxos.core.profile.AgentProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Safely loads YAML agent profiles, skipping only invalid files during startup scanning. */
public class ProfileLoader {
  private static final Logger LOG = LoggerFactory.getLogger(ProfileLoader.class);
  private final Path directory;
  private final Set<String> registeredProviders;

  private final boolean anyProvider;

  /** Lenient reader for listing only: every provider name is accepted. */
  public static ProfileLoader forListing(Path directory) {
    return new ProfileLoader(directory, Set.of(), true);
  }

  public ProfileLoader(Path directory, Set<String> registeredProviders) {
    this(directory, registeredProviders, false);
  }

  private ProfileLoader(Path directory, Set<String> registeredProviders, boolean anyProvider) {
    this.anyProvider = anyProvider;
    this.directory = directory;
    this.registeredProviders = Set.copyOf(registeredProviders);
  }

  public Map<String, AgentProfile> loadAll() {
    Map<String, AgentProfile> result = new LinkedHashMap<>();
    if (!Files.isDirectory(directory)) {
      LOG.warn("Profile directory not found: {}", directory);
      return result;
    }
    try (Stream<Path> paths = Files.list(directory)) {
      for (Path file :
          paths
              .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
              .sorted()
              .toList()) {
        try {
          AgentProfile p = loadOne(file);
          if (result.containsKey(p.name())) {
            throw new IllegalArgumentException("Duplicate profile name: " + p.name());
          }
          result.put(p.name(), p);
        } catch (RuntimeException | IOException problem) {
          LOG.error("Skipping invalid profile {}: {}", file.getFileName(), problem.toString());
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unable to list profile directory", e);
    }
    return Map.copyOf(result);
  }

  public AgentProfile loadOne(Path file) throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit(500_000);
    Yaml yaml = new Yaml(new SafeConstructor(options));
    Object raw;
    try (InputStream input = Files.newInputStream(file)) {
      raw = yaml.load(input);
    }
    Map<String, Object> src = asMap(raw);
    String name = required(src, "name");
    Map<String, Object> provider = asMap(src.get("provider"));
    String providerName = required(provider, "name");
    String model = required(provider, "model");
    if (!anyProvider && !registeredProviders.contains(providerName)) {
      throw new IllegalArgumentException(
          "Profile " + name + " uses unknown provider: " + providerName);
    }
    double temperature =
        provider.get("temperature") == null
            ? 0.2
            : Double.parseDouble(String.valueOf(provider.get("temperature")));
    if (temperature < 0 || temperature > 2) {
      throw new IllegalArgumentException("Invalid provider.temperature");
    }
    return new AgentProfile(
        name,
        String.valueOf(src.getOrDefault("description", "")),
        asMap(src.get("identity")),
        new AgentProfile.ProviderSettings(providerName, model, temperature),
        strings(src.get("tools")),
        strings(src.get("skills")),
        strings(src.get("mcp_servers")),
        maps(src.get("channels")),
        maps(src.get("notify_channels")),
        maps(src.get("schedules")),
        asMap(src.get("bootstrap")),
        asMap(src.get("settings")));
  }

  private static String required(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof String v) || v.isBlank()) {
      throw new IllegalArgumentException("Missing required field: " + key);
    }
    return v;
  }

  private static Map<String, Object> asMap(Object value) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> input)) {
      throw new IllegalArgumentException("Expected a YAML mapping");
    }
    Map<String, Object> output = new LinkedHashMap<>();
    for (var e : input.entrySet()) {
      if (!(e.getKey() instanceof String key)) {
        throw new IllegalArgumentException("YAML keys must be strings");
      }
      output.put(key, e.getValue());
    }
    return Map.copyOf(output);
  }

  private static List<String> strings(Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> items)) {
      throw new IllegalArgumentException("Expected a list");
    }
    List<String> out = new ArrayList<>();
    for (Object item : items) {
      out.add(String.valueOf(item));
    }
    return List.copyOf(out);
  }

  private static List<Map<String, Object>> maps(Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> items)) {
      throw new IllegalArgumentException("Expected a list of mappings");
    }
    return items.stream().map(ProfileLoader::asMap).toList();
  }
}
