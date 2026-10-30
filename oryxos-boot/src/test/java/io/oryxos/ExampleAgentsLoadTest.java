package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.provider.ProfileLoader;
import io.oryxos.provider.agent.AgentDefinition;
import io.oryxos.provider.agent.AgentLoader;
import io.oryxos.scheduler.ScheduleProfileValidator;
import io.oryxos.tool.BuiltinTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The three example agents exactly as they are committed (nothing rewritten for a test machine) are
 * valid: they pass the same loader, tool check, sandbox parser and schedule parser as any agent,
 * and they ask for no more than their job needs.
 */
class ExampleAgentsLoadTest {
  private static AgentLoader loader() {
    Set<String> tools = new TreeSet<>(Set.of("save_memory", "recall_memory"));
    BuiltinTools.describeOnly().forEach(tool -> tools.add(tool.getName()));
    ProfileLoader profiles = new ProfileLoader(Path.of("unused"), Set.of("deepseek", "mock"));
    return new AgentLoader(profiles, () -> tools, List.of(new ScheduleProfileValidator()));
  }

  private static AgentDefinition load(String name) {
    return loader().load(DemoSupport.EXAMPLES.resolve(name));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sandbox(AgentProfile profile) {
    return (Map<String, Object>) profile.settings().get("sandbox");
  }

  @Test
  void weatherDailyAsksForTwoToolsOneHostAndEightOClockInShanghai() {
    AgentDefinition agent = load("weather-daily");
    AgentProfile profile = agent.profile();

    assertEquals(List.of("http_get", "notify"), profile.tools());
    assertEquals("0 0 8 * * *", profile.schedules().getFirst().get("cron"));
    assertEquals("Asia/Shanghai", profile.schedules().getFirst().get("zone"));
    assertEquals(List.of("api.open-meteo.com", "127.0.0.1"), sandbox(profile).get("hosts"));
    assertFalse(sandbox(profile).containsKey("commands"));
    assertFalse(sandbox(profile).containsKey("write_roots"));
    assertEquals(List.of(), agent.resources(), "only AGENT.md");
    assertTrue(
        agent.instructions().contains("https://api.open-meteo.com/v1/forecast?latitude=39.9042"));
  }

  @Test
  void dailyTechDigestMayReadOnlyItsSkillsDirectoryAndReachOnlyTheNewsHost() {
    AgentDefinition agent = load("daily-tech-digest");
    AgentProfile profile = agent.profile();

    assertEquals(
        List.of("http_get", "file_read", "notify", "save_memory", "recall_memory"),
        profile.tools());
    assertEquals("0 0 9 * * *", profile.schedules().getFirst().get("cron"));
    assertEquals("Asia/Shanghai", profile.schedules().getFirst().get("zone"));
    assertEquals(
        List.of(".oryxos/agents/daily-tech-digest/skills"), sandbox(profile).get("read_roots"));
    assertEquals(List.of("hn.algolia.com", "127.0.0.1"), sandbox(profile).get("hosts"));
    assertEquals(List.of("skills/digest-format.md"), agent.resources());
    assertFalse(
        agent.instructions().contains("DIGEST-FORMAT-MARKER"),
        "the skill text is not part of the prompt");
  }

  @Test
  void githubDailyMayRunExactlyOneScriptWithFixedArgumentsAndFetchNothingItself() {
    AgentDefinition agent = load("github-daily");
    AgentProfile profile = agent.profile();

    assertEquals(List.of("shell_exec", "notify", "save_memory", "recall_memory"), profile.tools());
    assertFalse(
        profile.tools().contains("http_get"), "the script is the only thing that talks to GitHub");
    assertEquals("0 30 9 * * *", profile.schedules().getFirst().get("cron"));
    assertEquals("Asia/Shanghai", profile.schedules().getFirst().get("zone"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> commands =
        (List<Map<String, Object>>) sandbox(profile).get("commands");
    assertEquals(1, commands.size());
    assertEquals("python", commands.getFirst().get("name"));
    assertTrue(commands.getFirst().get("args").toString().contains("github_trending"));
    assertEquals(List.of("scripts/github_trending.py"), agent.resources());
    assertFalse(
        agent.instructions().contains("def build("), "the script source is not part of the prompt");
  }

  @Test
  void allThreeUseTheDefaultModelAndOnlyALocalReceiverUntilSomeoneAuthorisesMore() {
    for (String name : List.of("weather-daily", "daily-tech-digest", "github-daily")) {
      AgentProfile profile = load(name).profile();
      assertEquals("deepseek", profile.provider().name(), name);
      assertEquals(1, profile.notifyChannels().size(), name);
      assertEquals("local-receiver", profile.notifyChannels().getFirst().get("name"), name);
      assertTrue(
          String.valueOf(profile.notifyChannels().getFirst().get("url"))
              .startsWith("http://127.0.0.1:"),
          name + " notifies a local address by default");
      assertFalse(profile.tools().contains("file_write"), name);
      assertEquals(name, profile.name());
    }
  }
}
