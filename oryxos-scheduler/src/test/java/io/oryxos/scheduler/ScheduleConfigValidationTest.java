package io.oryxos.scheduler;

import static io.oryxos.scheduler.SchedulerFixtures.profile;
import static io.oryxos.scheduler.SchedulerFixtures.schedule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.schedule.ScheduleConfigException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A bad schedule must be rejected with a precise reason, never silently turned into a default. */
class ScheduleConfigValidationTest {
  private static List<ScheduleConfig> parse(Map<String, Object> entry) {
    return ScheduleConfigParser.parse(profile("weather", List.of(entry)));
  }

  private static String rejection(Map<String, Object> entry) {
    return assertThrows(ScheduleConfigException.class, () -> parse(entry)).getMessage();
  }

  @Test
  void aCompleteEntryIsAcceptedWithItsZone() {
    ScheduleConfig config =
        parse(schedule("morning", "0 0 9 * * *", "Asia/Shanghai", "Report the weather")).getFirst();
    assertEquals("morning", config.id());
    assertEquals(ZoneId.of("Asia/Shanghai"), config.zone());
    assertEquals("Report the weather", config.message());
  }

  @Test
  void aProfileWithoutSchedulesYieldsNothing() {
    assertTrue(ScheduleConfigParser.parse(profile("quiet", List.of())).isEmpty());
  }

  @Test
  void everyFieldIsRequiredAndMustBeText() {
    for (String missing : List.of("id", "cron", "zone", "message")) {
      Map<String, Object> entry = new java.util.HashMap<>(schedule("a", "0 0 9 * * *", "UTC", "m"));
      entry.remove(missing);
      assertTrue(rejection(entry).contains("'" + missing + "' is required"), missing);
    }
    Map<String, Object> numeric = new java.util.HashMap<>(schedule("a", "0 0 9 * * *", "UTC", "m"));
    numeric.put("cron", 42);
    assertTrue(rejection(numeric).contains("'cron' is required and must be text"));
    assertTrue(rejection(schedule("a", "0 0 9 * * *", "UTC", "   ")).contains("'message'"));
  }

  @Test
  void anInvalidCronIsRejectedIncludingTheFiveFieldUnixForm() {
    assertTrue(rejection(schedule("a", "not a cron", "UTC", "m")).contains("cron"));
    // Spring cron has six fields (seconds first); a five-field Unix cron would be misread.
    assertTrue(rejection(schedule("a", "0 9 * * *", "UTC", "m")).contains("six-field"));
    assertTrue(rejection(schedule("a", "0 0 25 * * *", "UTC", "m")).contains("cron"));
  }

  @Test
  void anInvalidOrMissingTimeZoneIsRejected() {
    assertTrue(rejection(schedule("a", "0 0 9 * * *", "Mars/Olympus", "m")).contains("zone"));
    assertTrue(rejection(schedule("a", "0 0 9 * * *", "", "m")).contains("'zone' is required"));
  }

  @Test
  void aTaskIdWithUnsafeCharactersIsRejected() {
    for (String bad : List.of("has space", "dot.ted", "slash/y", "ünï", "x".repeat(65))) {
      assertTrue(
          rejection(schedule(bad, "0 0 9 * * *", "UTC", "m")).contains("id must match"), bad);
    }
  }

  @Test
  void unknownKeysAreRejectedSoTyposDoNotPassSilently() {
    Map<String, Object> typo = new java.util.HashMap<>(schedule("a", "0 0 9 * * *", "UTC", "m"));
    typo.put("timezone", "UTC");
    assertTrue(rejection(typo).contains("unknown key 'timezone'"));
  }

  @Test
  void aCronThatFiresTooOftenIsRejected() {
    assertTrue(rejection(schedule("a", "* * * * * *", "UTC", "m")).contains("more often"));
    assertTrue(rejection(schedule("a", "*/5 * * * * *", "UTC", "m")).contains("more often"));
    // Exactly at the limit is fine.
    parse(schedule("a", "*/30 * * * * *", "UTC", "m"));
  }

  @Test
  void aCronThatNeverFiresIsRejected() {
    assertTrue(rejection(schedule("a", "0 0 0 30 2 *", "UTC", "m")).contains("never fires"));
  }

  @Test
  void aMessageThatIsTooLongIsRejected() {
    assertTrue(
        rejection(schedule("a", "0 0 9 * * *", "UTC", "x".repeat(2001))).contains("longer than"));
  }

  @Test
  void duplicateIdsInOneProfileAreRejectedButTheSameIdInTwoProfilesIsFine() {
    var entries =
        List.of(
            schedule("daily", "0 0 9 * * *", "UTC", "one"),
            schedule("daily", "0 0 10 * * *", "UTC", "two"));
    ScheduleConfigException error =
        assertThrows(
            ScheduleConfigException.class,
            () -> ScheduleConfigParser.parse(profile("weather", entries)));
    assertTrue(error.getMessage().contains("duplicate id 'daily'"), error.getMessage());

    assertEquals("weather.daily", ScheduleConfigParser.taskId("weather", "daily"));
    assertEquals("news.daily", ScheduleConfigParser.taskId("news", "daily"));
  }

  @Test
  void aProfileNameThatCannotFormASessionIdIsRejected() {
    ScheduleConfigException error =
        assertThrows(
            ScheduleConfigException.class,
            () ->
                ScheduleConfigParser.parse(
                    profile("has.dot", List.of(schedule("a", "0 0 9 * * *", "UTC", "m")))));
    assertTrue(error.getMessage().contains("session id"), error.getMessage());
  }

  @Test
  void errorsNameTheProfileAndTheEntryPosition() {
    var entries =
        List.of(schedule("ok", "0 0 9 * * *", "UTC", "m"), schedule("bad", "nope", "UTC", "m"));
    String message =
        assertThrows(
                ScheduleConfigException.class,
                () -> ScheduleConfigParser.parse(profile("weather", entries)))
            .getMessage();
    assertTrue(message.startsWith("Profile 'weather' schedules[1]: "), message);
  }
}
