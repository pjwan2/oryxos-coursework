package io.oryxos;

import io.oryxos.core.sandbox.Sandbox;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.AllowListSandbox;
import io.oryxos.tool.BuiltinTools;
import io.oryxos.tool.HttpGetTool;
import io.oryxos.tool.SandboxPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the sandbox from {@code oryxos.sandbox.*} (default: only the workspace directory, no
 * commands, no hosts) and the single ToolRegistry holding built-in plus any extra OryxTool beans.
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

    public long getShellTimeoutSeconds() {
      return shellTimeoutSeconds;
    }

    public void setShellTimeoutSeconds(long shellTimeoutSeconds) {
      this.shellTimeoutSeconds = shellTimeoutSeconds;
    }
  }

  @Bean
  public Sandbox sandbox(SandboxProperties props) {
    return new AllowListSandbox(
        new SandboxPolicy(
            props.getReadRoots().stream().map(Path::of).toList(),
            props.getWriteRoots().stream().map(Path::of).toList(),
            Set.copyOf(props.getCommands()),
            Set.copyOf(props.getHosts())));
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
