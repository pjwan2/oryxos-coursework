package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Two messages for one conversation used to read the same history, each append its answer and save,
 * so the later save silently dropped the earlier answer. Messages of one session now run one after
 * the other; different sessions still run side by side.
 */
class AgentSessionConcurrencyTest {
  private static final AgentProfile PROFILE = AgentServiceTest.profile();
  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void shutDown() {
    pool.shutdownNow();
  }

  /** Behaves like a database: load hands out a copy, save replaces the stored transcript. */
  private static final class CopyingStore implements AgentSessionStore {
    private final Map<String, List<AgentMessage>> rows = new HashMap<>();

    @Override
    public synchronized AgentSession loadOrCreate(String channel, String user, String profile) {
      String id = SessionIds.of(channel, user, profile);
      return AgentSession.restore(id, profile, rows.getOrDefault(id, List.of()));
    }

    @Override
    public synchronized void save(AgentSession session) {
      rows.put(session.id(), session.transcript());
    }

    synchronized List<AgentMessage> transcript(String id) {
      return rows.getOrDefault(id, List.of());
    }

    @Override
    public synchronized Optional<AgentSession> find(String id) {
      return Optional.empty();
    }

    @Override
    public List<SessionSummary> list(int limit) {
      return List.of();
    }

    @Override
    public boolean discard(String id) {
      return false;
    }

    @Override
    public long count() {
      return 0;
    }
  }

  /** Answers "answer to <last user text>" after a pause, and watches how many calls overlap. */
  private static final class SlowModel implements ModelGateway {
    final AtomicInteger inside = new AtomicInteger();
    final AtomicInteger maxInside = new AtomicInteger();
    final Map<String, AtomicInteger> maxPerSession = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> insidePerSession = new ConcurrentHashMap<>();
    final long pauseMillis;

    SlowModel(long pauseMillis) {
      this.pauseMillis = pauseMillis;
    }

    @Override
    public ModelTurn chat(
        String sessionId, AgentProfile profile, List<AgentMessage> prompt, List<ToolSpec> tools) {
      int now = inside.incrementAndGet();
      maxInside.accumulateAndGet(now, Math::max);
      int mine =
          insidePerSession.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
      maxPerSession
          .computeIfAbsent(sessionId, k -> new AtomicInteger())
          .accumulateAndGet(mine, Math::max);
      try {
        Thread.sleep(pauseMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      String lastUser = "";
      for (AgentMessage m : prompt) {
        if (m.role() == AgentMessage.Role.USER) {
          lastUser = m.content();
        }
      }
      insidePerSession.get(sessionId).decrementAndGet();
      inside.decrementAndGet();
      return ModelTurn.finalAnswer("answer to " + lastUser);
    }
  }

  private AgentService service(SlowModel model, CopyingStore store) {
    AgentToolExecutor none =
        new AgentToolExecutor() {
          @Override
          public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
            return List.of();
          }

          @Override
          public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
            return ToolOutput.failed("none", false);
          }
        };
    ReActLoop loop = new ReActLoop(model, none, new PromptBuilder(Clock.systemUTC(), 50));
    return new AgentService(loop, name -> PROFILE, store);
  }

  private static List<String> texts(List<AgentMessage> messages) {
    return messages.stream().map(AgentMessage::content).toList();
  }

  @Test
  void twoSimultaneousMessagesToOneSessionKeepBothAnswersAndRunOneAtATime() throws Exception {
    SlowModel model = new SlowModel(150);
    CopyingStore store = new CopyingStore();
    AgentService agent = service(model, store);
    CountDownLatch go = new CountDownLatch(1);

    Future<String> first =
        pool.submit(
            () -> {
              go.await();
              return agent.process("api", "u", "payment-demo", "first");
            });
    Future<String> second =
        pool.submit(
            () -> {
              go.await();
              return agent.process("api", "u", "payment-demo", "second");
            });
    go.countDown();
    assertEquals("answer to first", first.get(10, TimeUnit.SECONDS));
    assertEquals("answer to second", second.get(10, TimeUnit.SECONDS));

    List<String> history = texts(store.transcript("api:u:payment-demo"));
    assertEquals(4, history.size(), "no answer was overwritten: " + history);
    assertTrue(
        history.equals(List.of("first", "answer to first", "second", "answer to second"))
            || history.equals(List.of("second", "answer to second", "first", "answer to first")),
        "each question is followed by its own answer: " + history);
    assertEquals(1, model.maxPerSession.get("api:u:payment-demo").get(), "never two calls at once");
  }

  @Test
  void manyMessagesToOneSessionAllArriveInTheHistoryExactlyOnce() throws Exception {
    SlowModel model = new SlowModel(5);
    CopyingStore store = new CopyingStore();
    AgentService agent = service(model, store);
    int count = 20;
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> results = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String text = "m" + i;
      results.add(
          pool.submit(
              () -> {
                go.await();
                return agent.process("api", "u", "payment-demo", text);
              }));
    }
    go.countDown();
    for (Future<String> r : results) {
      r.get(30, TimeUnit.SECONDS);
    }

    List<AgentMessage> history = store.transcript("api:u:payment-demo");
    assertEquals(count * 2, history.size());
    for (int i = 0; i < history.size(); i += 2) {
      assertEquals(AgentMessage.Role.USER, history.get(i).role());
      assertEquals("answer to " + history.get(i).content(), history.get(i + 1).content());
    }
    assertEquals(
        count, history.stream().filter(m -> m.role() == AgentMessage.Role.USER).distinct().count());
  }

  @Test
  void differentSessionsStillRunAtTheSameTime() throws Exception {
    SlowModel model = new SlowModel(300);
    CopyingStore store = new CopyingStore();
    AgentService agent = service(model, store);
    CountDownLatch go = new CountDownLatch(1);
    Future<String> a =
        pool.submit(
            () -> {
              go.await();
              return agent.process("api", "alice", "payment-demo", "hello");
            });
    Future<String> b =
        pool.submit(
            () -> {
              go.await();
              return agent.process("api", "bob", "payment-demo", "hello");
            });
    go.countDown();
    a.get(10, TimeUnit.SECONDS);
    b.get(10, TimeUnit.SECONDS);

    assertEquals(2, model.maxInside.get(), "unrelated sessions do not wait for each other");
  }

  @Test
  void aFailingRunReleasesTheSessionForTheNextMessage() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ModelGateway flaky =
        (sessionId, profile, prompt, tools) -> {
          if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("provider down");
          }
          return ModelTurn.finalAnswer("recovered");
        };
    CopyingStore store = new CopyingStore();
    AgentToolExecutor none =
        new AgentToolExecutor() {
          @Override
          public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
            return List.of();
          }

          @Override
          public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
            return ToolOutput.failed("none", false);
          }
        };
    AgentService agent =
        new AgentService(
            new ReActLoop(flaky, none, new PromptBuilder(Clock.systemUTC(), 50)),
            name -> PROFILE,
            store);

    try {
      agent.process("api", "u", "payment-demo", "one");
    } catch (IllegalStateException expected) {
      assertEquals("provider down", expected.getMessage());
    }
    Future<String> next = pool.submit(() -> agent.process("api", "u", "payment-demo", "two"));

    assertEquals(
        "recovered", next.get(5, TimeUnit.SECONDS), "the failed run did not keep the lock");
  }
}
