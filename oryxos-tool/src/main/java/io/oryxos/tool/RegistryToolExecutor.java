package io.oryxos.tool;

import io.oryxos.core.agent.AgentToolExecutor;
import io.oryxos.core.agent.ToolAction;
import io.oryxos.core.agent.ToolOutput;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.sandbox.SandboxViolation;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolContext;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolSpec;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single place where tools run. The Profile allowlist is checked first; tools the Profile does
 * not list are neither advertised to the model nor executable.
 */
public final class RegistryToolExecutor implements AgentToolExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(RegistryToolExecutor.class);
  private final ToolRegistry registry;

  public RegistryToolExecutor(ToolRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public List<ToolSpec> allowedSpecifications(AgentProfile profile) {
    Objects.requireNonNull(profile, "profile");
    return registry.specs().stream().filter(spec -> profile.tools().contains(spec.name())).toList();
  }

  @Override
  public ToolOutput execute(String sessionId, AgentProfile profile, ToolAction action) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(action, "action");
    if (!profile.tools().contains(action.name())) {
      throw new SecurityException("Tool not permitted: " + action.name());
    }
    OryxTool tool = registry.find(action.name()).orElse(null);
    if (tool == null) {
      return ToolOutput.failed("Tool is not installed: " + action.name(), false);
    }
    try {
      return Objects.requireNonNull(
          tool.execute(action.argumentsJson(), new ToolContext(sessionId, profile)));
    } catch (SandboxViolation denied) {
      return ToolOutput.denied(denied.getMessage());
    } catch (RuntimeException failure) {
      // A bug in one tool must not kill the whole agent turn; the audit still records a failure.
      // Never log the exception itself: its message can carry a URL token or an API key. The class
      // and the throwing line are enough to find the bug.
      StackTraceElement at = failure.getStackTrace().length > 0 ? failure.getStackTrace()[0] : null;
      LOG.error("Tool {} failed with {} at {}", action.name(), failure.getClass().getName(), at);
      return ToolOutput.failed("Tool error: " + failure.getClass().getSimpleName(), false);
    }
  }
}
