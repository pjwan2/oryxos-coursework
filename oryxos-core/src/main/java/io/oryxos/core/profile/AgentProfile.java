package io.oryxos.core.profile;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable snapshot of per-agent configuration; credentials belong in global configuration. */
public record AgentProfile(
    String name,
    String description,
    Map<String, Object> identity,
    ProviderSettings provider,
    List<String> tools,
    List<String> skills,
    List<String> mcpServers,
    List<Map<String, Object>> channels,
    List<Map<String, Object>> notifyChannels,
    List<Map<String, Object>> schedules,
    Map<String, Object> bootstrap,
    Map<String, Object> settings) {

  /** Defensive snapshots prevent a YAML parser or caller from mutating a registered profile. */
  public AgentProfile(
      String name,
      String description,
      Map<String, Object> identity,
      ProviderSettings provider,
      List<String> tools,
      List<String> skills,
      List<String> mcpServers,
      List<Map<String, Object>> channels,
      List<Map<String, Object>> notifyChannels,
      List<Map<String, Object>> schedules,
      Map<String, Object> bootstrap,
      Map<String, Object> settings) {
    this.name = Objects.requireNonNull(name, "name");
    this.description = Objects.requireNonNull(description, "description");
    this.identity = freezeMap(identity);
    this.provider = Objects.requireNonNull(provider, "provider");
    this.tools = List.copyOf(tools);
    this.skills = List.copyOf(skills);
    this.mcpServers = List.copyOf(mcpServers);
    this.channels = freezeMapList(channels);
    this.notifyChannels = freezeMapList(notifyChannels);
    this.schedules = freezeMapList(schedules);
    this.bootstrap = freezeMap(bootstrap);
    this.settings = freezeMap(settings);
  }

  // Explicit accessors keep the record's List/Map components encapsulated.
  @Override
  public Map<String, Object> identity() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(identity));
  }

  @Override
  public List<String> tools() {
    return List.copyOf(tools);
  }

  @Override
  public List<String> skills() {
    return List.copyOf(skills);
  }

  @Override
  public List<String> mcpServers() {
    return List.copyOf(mcpServers);
  }

  @Override
  public List<Map<String, Object>> channels() {
    return List.copyOf(channels);
  }

  @Override
  public List<Map<String, Object>> notifyChannels() {
    return List.copyOf(notifyChannels);
  }

  @Override
  public List<Map<String, Object>> schedules() {
    return List.copyOf(schedules);
  }

  @Override
  public Map<String, Object> bootstrap() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(bootstrap));
  }

  @Override
  public Map<String, Object> settings() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(settings));
  }

  private static List<Map<String, Object>> freezeMapList(List<Map<String, Object>> source) {
    Objects.requireNonNull(source, "profile list");
    List<Map<String, Object>> result = new ArrayList<>(source.size());
    for (Map<String, Object> entry : source) {
      result.add(freezeMap(entry));
    }
    return List.copyOf(result);
  }

  private static Map<String, Object> freezeMap(Map<?, ?> source) {
    Objects.requireNonNull(source, "profile map");
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("Profile configuration keys must be strings");
      }
      result.put(key, freezeValue(entry.getValue()));
    }
    return Collections.unmodifiableMap(result);
  }

  private static Object freezeValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return freezeMap(map);
    }
    if (value instanceof List<?> list) {
      List<Object> values = new ArrayList<>(list.size());
      for (Object item : list) {
        values.add(freezeValue(item));
      }
      return Collections.unmodifiableList(values);
    }
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Double
        || value instanceof Float
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigDecimal
        || value instanceof BigInteger) {
      return value;
    }
    throw new IllegalArgumentException(
        "Profile configuration only supports immutable YAML scalars, maps, and lists: "
            + value.getClass().getSimpleName());
  }

  public record ProviderSettings(String name, String model, double temperature) {}

  public static AgentProfile example(String providerName, String modelName) {
    return new AgentProfile(
        "test-agent",
        "Test agent",
        Map.of(),
        new ProviderSettings(providerName, modelName, 0.2),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }
}
