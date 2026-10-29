package io.oryxos.tool;

import io.oryxos.core.profile.AgentProfile;
import java.util.List;
import java.util.Map;

/** Builds Profiles that declare (or do not declare) a sandbox, as a YAML file would. */
final class TestProfiles {
  private TestProfiles() {}

  static AgentProfile with(
      String name,
      List<String> tools,
      Map<String, Object> sandbox,
      List<Map<String, Object>> notify) {
    Map<String, Object> settings = sandbox == null ? Map.of() : Map.of("sandbox", sandbox);
    return new AgentProfile(
        name,
        "test profile " + name,
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "m", 0.1),
        tools,
        List.of(),
        List.of(),
        List.of(),
        notify,
        List.of(),
        Map.of(),
        settings);
  }

  static AgentProfile with(String name, Map<String, Object> sandbox) {
    return with(name, List.of(), sandbox, List.of());
  }
}
