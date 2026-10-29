package io.oryxos.tool;

import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolCallAudit;
import io.oryxos.core.agent.ToolCallEvent;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.tool.ToolSpec;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Decorator: one audited attempt per requested tool. The Provider never executes tools. */
public final class AuditedToolExecutor implements AgentToolExecutor {
  private final AgentToolExecutor delegate;
  private final ToolCallAudit audit;

  public AuditedToolExecutor(AgentToolExecutor delegate, ToolCallAudit audit) {
    this.delegate = Objects.requireNonNull(delegate);
    this.audit = Objects.requireNonNull(audit);
  }

  @Override
  public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
    return delegate.allowedSpecifications(profile);
  }

  @Override
  public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(action);
    long started = System.nanoTime();
    ToolOutput output;
    try {
      output = Objects.requireNonNull(delegate.execute(sessionId, profile, action));
    } catch (RuntimeException error) {
      audit.record(
          new ToolCallEvent(
              sessionId,
              action.name(),
              false,
              error.getClass().getSimpleName(),
              elapsed(started),
              Instant.now()));
      throw error;
    }
    audit.record(
        new ToolCallEvent(
            sessionId,
            action.name(),
            output.success(),
            categoryOf(output),
            elapsed(started),
            Instant.now()));
    return output;
  }

  private static String categoryOf(ToolOutput output) {
    if (output.success()) {
      return null;
    }
    return output.isDenied() ? "SandboxDenied" : "ToolReturnedFailure";
  }

  private static long elapsed(long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }
}
