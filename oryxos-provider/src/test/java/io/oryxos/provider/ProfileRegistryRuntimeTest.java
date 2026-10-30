package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ProfileNotFoundException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProfileRegistryRuntimeTest {
  private static AgentProfile profile(String name, String description) {
    return new AgentProfile(
        name,
        description,
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "m", 0.2),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }

  @Test
  void registerGetExistsListAndRemoveWork() {
    ProfileRegistry registry = new ProfileRegistry(Map.of("a", profile("a", "first")));

    assertTrue(registry.exists("a"));
    assertFalse(registry.exists("b"));
    registry.register(profile("b", "second"));

    assertEquals("second", registry.get("b").description());
    assertEquals(List.of("a", "b"), registry.list().stream().map(AgentProfile::name).toList());
    assertTrue(registry.remove("b"));
    assertFalse(registry.remove("b"), "removing twice is not an error");
    assertThrows(ProfileNotFoundException.class, () -> registry.get("b"));
  }

  @Test
  void registeringAnExistingNameIsAConflictNotAnOverwrite() {
    ProfileRegistry registry = new ProfileRegistry(Map.of("a", profile("a", "first")));

    assertThrows(AgentDefinitionException.class, () -> registry.register(profile("a", "second")));

    assertEquals("first", registry.get("a").description());
  }

  @Test
  void replaceNeedsAnExistingAgentAndReturnsTheOldOne() {
    ProfileRegistry registry = new ProfileRegistry(Map.of("a", profile("a", "first")));

    AgentProfile old = registry.replace(profile("a", "second"));

    assertEquals("first", old.description());
    assertEquals("second", registry.get("a").description());
    assertThrows(ProfileNotFoundException.class, () -> registry.replace(profile("zzz", "x")));
  }

  @Test
  void listenersSeeTheBeforeAndAfterOfEveryChange() {
    ProfileRegistry registry = new ProfileRegistry(Map.of());
    List<String> seen = new ArrayList<>();
    registry.addListener(
        (name, before, after) ->
            seen.add(
                name
                    + ":"
                    + (before == null ? "-" : before.description())
                    + ">"
                    + (after == null ? "-" : after.description())));

    registry.register(profile("a", "v1"));
    registry.replace(profile("a", "v2"));
    registry.remove("a");

    assertEquals(List.of("a:->v1", "a:v1>v2", "a:v2>-"), seen);
  }

  @Test
  void anAgentIsAlreadyInTheRegistryWhenItsListenerRuns() {
    ProfileRegistry registry = new ProfileRegistry(Map.of());
    AtomicBoolean visible = new AtomicBoolean();
    registry.addListener((name, before, after) -> visible.set(registry.exists(name)));

    registry.register(profile("a", "v1"));

    assertTrue(visible.get(), "listeners run after the swap");
  }

  @Test
  void readersNeverSeeAHalfUpdatedRegistryWhileWritersChurn() throws Exception {
    ProfileRegistry registry = new ProfileRegistry(Map.of("stable", profile("stable", "s")));
    registry.register(profile("moving", "v0"));
    AtomicBoolean stop = new AtomicBoolean();
    CountDownLatch ready = new CountDownLatch(4);
    ExecutorService pool = Executors.newFixedThreadPool(5);
    List<Future<String>> readers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      readers.add(
          pool.submit(
              () -> {
                ready.countDown();
                while (!stop.get()) {
                  Map<String, AgentProfile> snapshot = registry.all();
                  if (!snapshot.containsKey("stable")) {
                    return "stable agent vanished";
                  }
                  AgentProfile moving = snapshot.get("moving");
                  if (moving != null && !moving.description().startsWith("v")) {
                    return "torn value " + moving.description();
                  }
                  if (registry.list().stream().noneMatch(p -> p.name().equals("stable"))) {
                    return "stable agent missing from list";
                  }
                }
                return null;
              }));
    }
    Future<?> writer =
        pool.submit(
            () -> {
              ready.await();
              for (int i = 1; i <= 2000; i++) {
                registry.replace(profile("moving", "v" + i));
                if (i % 50 == 0) {
                  registry.remove("moving");
                  registry.register(profile("moving", "v" + i));
                }
              }
              return null;
            });
    writer.get(30, TimeUnit.SECONDS);
    stop.set(true);
    for (Future<String> reader : readers) {
      assertNull(reader.get(30, TimeUnit.SECONDS));
    }
    pool.shutdownNow();
    assertEquals("v2000", registry.get("moving").description());
  }

  @Test
  void concurrentRegistrationOfTheSameNameLetsExactlyOneWin() throws Exception {
    ProfileRegistry registry = new ProfileRegistry(Map.of());
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String description = "writer" + i;
      results.add(
          pool.submit(
              () -> {
                go.await();
                try {
                  registry.register(profile("race", description));
                  return true;
                } catch (AgentDefinitionException lost) {
                  return false;
                }
              }));
    }
    go.countDown();
    int winners = 0;
    for (Future<Boolean> result : results) {
      if (result.get(10, TimeUnit.SECONDS)) {
        winners++;
      }
    }
    pool.shutdownNow();
    assertEquals(1, winners);
    assertEquals(1, registry.list().size());
  }
}
