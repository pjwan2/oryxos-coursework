package io.oryxos.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentProfileImmutabilityTest {
  @Test
  void callerCannotModifyStoredFieldsOrNestedConfiguration() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("language", "zh");
    List<String> values = new ArrayList<>(List.of("item-1"));
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("nested", nested);
    identity.put("values", values);
    List<String> tools = new ArrayList<>(List.of("http_get"));
    Map<String, Object> firstSchedule = new LinkedHashMap<>();
    firstSchedule.put("cron", "0 0 9 * * *");
    List<Map<String, Object>> schedules = new ArrayList<>(List.of(firstSchedule));

    AgentProfile profile =
        new AgentProfile(
            "agent",
            "Test agent",
            identity,
            new AgentProfile.ProviderSettings("mock", "mock-echo", 0.2),
            tools,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            schedules,
            Map.of(),
            Map.of());

    // Changing the YAML parser's collections after loading must not change the profile.
    nested.put("language", "en");
    values.add("item-2");
    tools.add("shell");
    firstSchedule.put("cron", "0 0 * * * *");
    schedules.clear();

    Map<?, ?> capturedNested = (Map<?, ?>) profile.identity().get("nested");
    List<?> capturedValues = (List<?>) profile.identity().get("values");
    assertEquals("zh", capturedNested.get("language"));
    assertEquals(List.of("item-1"), capturedValues);
    assertEquals(List.of("http_get"), profile.tools());
    assertEquals("0 0 9 * * *", profile.schedules().getFirst().get("cron"));

    // Records must not expose writable containers, even through nested objects.
    assertThrows(UnsupportedOperationException.class, profile.tools()::clear);
    assertThrows(UnsupportedOperationException.class, profile.identity()::clear);
    assertThrows(UnsupportedOperationException.class, capturedNested::clear);
    assertThrows(UnsupportedOperationException.class, capturedValues::clear);
    assertThrows(UnsupportedOperationException.class, profile.schedules().getFirst()::clear);
  }
}
