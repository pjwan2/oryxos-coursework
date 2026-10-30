package io.oryxos;

import io.oryxos.core.profile.ProfileChangeListener;
import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.sandbox.SandboxConfigException;
import io.oryxos.core.sandbox.SandboxSpec;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.BuiltinTools;
import io.oryxos.tool.HttpGetTool;
import io.oryxos.tool.ProfileSandbox;
import io.oryxos.tool.SandboxPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the operator's sandbox CEILING from {@code oryxos.sandbox.*} (default: only the workspace
 * directory, no commands, no hosts) and the single ToolRegistry holding built-in plus any extra
 * OryxTool beans. The ceiling grants nothing by itself: each Profile must declare what it needs
 * under {@code settings.sandbox}, and gets the intersection.
 */
@Configuration
@EnableConfigurationProperties(ToolConfiguration.SandboxProperties.class)
public class ToolConfiguration {

  /** Everything not listed here is denied. Paths are resolved against the working directory. */
  @ConfigurationProperties(prefix = "oryxos.sandbox")
  public static class SandboxProperties {
    private List<String> readRoots = List.of(".oryxos/workspace");
    private List<String> writeRoots = List.of(".oryxos/workspace");
    private List<String> commands = List.of();
    private List<String> hosts = List.of();
    private long shellTimeoutSeconds = 10;
    private boolean allowPrivateNetworks;

    public List<String> getReadRoots() {
      return List.copyOf(readRoots);
    }

    public void setReadRoots(List<String> readRoots) {
      this.readRoots = List.copyOf(readRoots);
    }

    public List<String> getWriteRoots() {
      return List.copyOf(writeRoots);
    }

    public void setWriteRoots(List<String> writeRoots) {
      this.writeRoots = List.copyOf(writeRoots);
    }

    public List<String> getCommands() {
      return List.copyOf(commands);
    }

    public void setCommands(List<String> commands) {
      this.commands = List.copyOf(commands);
    }

    public List<String> getHosts() {
      return List.copyOf(hosts);
    }

    public void setHosts(List<String> hosts) {
      this.hosts = List.copyOf(hosts);
    }

    public boolean isAllowPrivateNetworks() {
      return allowPrivateNetworks;
    }

    public void setAllowPrivateNetworks(boolean allowPrivateNetworks) {
      this.allowPrivateNetworks = allowPrivateNetworks;
    }

    public long getShellTimeoutSeconds() {
      return shellTimeoutSeconds;
    }

    public void setShellTimeoutSeconds(long shellTimeoutSeconds) {
      this.shellTimeoutSeconds = shellTimeoutSeconds;
    }
  }

  /**
   * The operator's ceiling. Invalid entries (a filesystem root, "*" hosts, path-style commands, bad
   * timeouts) stop the application from starting. Profiles then declare what they need inside it.
   */
  @Bean
  public Sandbox sandbox(SandboxProperties props) {
    if (props.getShellTimeoutSeconds() < 1 || props.getShellTimeoutSeconds() > 300) {
      throw new SandboxConfigException("oryxos.sandbox.shell-timeout-seconds must be 1..300");
    }
    Map<String, Object> declared = new LinkedHashMap<>();
    declared.put("read_roots", props.getReadRoots());
    declared.put("write_roots", props.getWriteRoots());
    declared.put("commands", props.getCommands());
    declared.put("hosts", props.getHosts());
    declared.put("allow_private_networks", props.isAllowPrivateNetworks());
    SandboxSpec spec = SandboxSpec.parse(declared, "oryxos.sandbox");
    return new ProfileSandbox(SandboxPolicy.ceilingFromSpec(spec));
  }

  /** A changed or removed agent loses its cached sandbox policy at once. */
  @Bean
  public ProfileChangeListener sandboxInvalidation(Sandbox sandbox) {
    return (name, before, after) -> sandbox.invalidate(name);
  }

  @Bean
  public ToolRegistry toolRegistry(
      Sandbox sandbox, SandboxProperties props, ObjectProvider<OryxTool> extraTools) {
    List<OryxTool> all =
        new ArrayList<>(
            BuiltinTools.create(
                sandbox,
                HttpGetTool.defaultClient(),
                Duration.ofSeconds(props.getShellTimeoutSeconds())));
    extraTools.orderedStream().forEach(all::add);
    return new ToolRegistry(all);
  }
}
