package io.oryxos.core.profile;

import java.util.List;
import java.util.Objects;

/**
 * What the admin API and console show about one agent. Webhook targets, sandbox paths and other
 * free-form settings are left out on purpose. {@code origin} is {@code agent-md} (editable) or
 * {@code legacy-yaml} (read-only); {@code privateMemory} says whether it has its own long-term
 * memory or shares the global one; {@code problem} is the last load error, if any.
 */
public record AgentView(
    String name,
    String description,
    String origin,
    boolean editable,
    String provider,
    String model,
    double temperature,
    List<String> tools,
    List<String> skills,
    List<Schedule> schedules,
    List<String> resources,
    boolean privateMemory,
    String instructions,
    String problem) {
  public static final String ORIGIN_AGENT_MD = "agent-md";
  public static final String ORIGIN_LEGACY = "legacy-yaml";

  /** One declared schedule. */
  public record Schedule(String id, String cron, String zone, String message) {}

  public AgentView {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(origin, "origin");
    tools = List.copyOf(tools);
    skills = List.copyOf(skills);
    schedules = List.copyOf(schedules);
    resources = List.copyOf(resources);
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
  public List<Schedule> schedules() {
    return List.copyOf(schedules);
  }

  @Override
  public List<String> resources() {
    return List.copyOf(resources);
  }
}
