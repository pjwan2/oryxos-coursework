package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentServiceTest {
  static AgentProfile profile() {
    return new AgentProfile(
        "payment-demo",
        "demo",
        Map.of(),
        new AgentProfile.ProviderSettings("mock", "mock", 0.2),
        List.of("get_payment"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }

  static final class MemorySessions implements AgentSessionStore {
    private final Map<String, AgentSession> sessions = new HashMap<>();

    @Override
    public AgentSession loadOrCreate(String ch, String user, String profile) {
      String id = SessionIds.of(ch, user, profile);
      return sessions.computeIfAbsent(id, key -> new AgentSession(key, profile));
    }

    @Override
    public void save(AgentSession session) {
      sessions.put(session.id(), session);
    }

    @Override
    public Optional<AgentSession> find(String id) {
      return Optional.ofNullable(sessions.get(id));
    }
  }

  private static ReActLoop loop(ModelGateway model) {
    AgentToolExecutor executor =
        new AgentToolExecutor() {
          @Override
          public List<ToolSpec> allowedSpecifications(AgentProfile p) {
            return List.of();
          }

          @Override
          public ToolOutput execute(String id, AgentProfile p, ToolAction a) {
            return ToolOutput.ok("ok");
          }
        };
    return new ReActLoop(model, executor, new PromptBuilder(Clock.systemUTC(), 20));
  }

  @Test
  void persistedAndContextClearedAfterSuccess() {
    MemorySessions store = new MemorySessions();
    ModelGateway model =
        (id, p, messages, specs) -> {
          assertEquals("payment-demo", ProfileContext.current().name());
          return ModelTurn.finalAnswer("hello");
        };
    AgentService service = new AgentService(loop(model), n -> profile(), store);
    assertEquals("hello", service.process("web", "u1", "payment-demo", "hi"));
    assertNull(ProfileContext.current());
    assertEquals(2, store.find("web:u1:payment-demo").orElseThrow().transcript().size());
  }

  @Test
  void modelFailureStillSavesUserTurnAndClearsContext() {
    MemorySessions store = new MemorySessions();
    ModelGateway broken =
        (id, p, messages, specs) -> {
          throw new IllegalStateException("model is down");
        };
    AgentService service = new AgentService(loop(broken), n -> profile(), store);
    assertThrows(
        IllegalStateException.class, () -> service.process("web", "u2", "payment-demo", "hi"));
    assertNull(ProfileContext.current());
    assertEquals(1, store.find("web:u2:payment-demo").orElseThrow().transcript().size());
  }

  @Test
  void sessionIdPreventsAmbiguity() {
    assertThrows(IllegalArgumentException.class, () -> SessionIds.of("web", "a:b", "demo"));
    assertTrue(SessionIds.of("web", "user", "demo").contains("user"));
  }
}
