package io.oryxos;

import io.oryxos.cli.CliRuntime;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.provider.ProfileLoader;
import io.oryxos.tool.BuiltinTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;

/**
 * Light commands read files directly and never start Spring. {@code chat} starts a web-less Spring
 * context on first use; {@code serve} starts the normal web application.
 */
public final class BootCliRuntime implements CliRuntime {
  static final String VERSION = "0.1.0";
  private static final String DEFAULT_PROFILE_DIR = ".oryxos/profiles";
  private static final String DEFAULT_JDBC = "jdbc:sqlite:./oryxos-dev.db";

  /** Spring properties applied to the chat context (tests use this to isolate storage). */
  private final Map<String, String> overrides;

  private ConfigurableApplicationContext context;

  public BootCliRuntime(Map<String, String> overrides) {
    this.overrides = Map.copyOf(Objects.requireNonNull(overrides, "overrides"));
  }

  private String profileDirectory() {
    String configured = overrides.get("oryxos.profile-directory");
    if (configured == null) {
      configured = System.getenv("ORYXOS_PROFILE_DIR");
    }
    return configured == null || configured.isBlank() ? DEFAULT_PROFILE_DIR : configured;
  }

  private String jdbcUrl() {
    String configured = overrides.get("spring.datasource.url");
    if (configured == null) {
      configured = System.getenv("ORYXOS_JDBC_URL");
    }
    return configured == null || configured.isBlank() ? DEFAULT_JDBC : configured;
  }

  @Override
  public List<ProfileSummary> listProfiles() {
    Map<String, AgentProfile> profiles =
        ProfileLoader.forListing(Path.of(profileDirectory())).loadAll();
    List<ProfileSummary> result = new ArrayList<>();
    profiles.values().stream()
        .sorted((a, b) -> a.name().compareTo(b.name()))
        .forEach(
            p ->
                result.add(
                    new ProfileSummary(
                        p.name(), p.provider().name(), p.provider().model(), p.tools())));
    return List.copyOf(result);
  }

  @Override
  public List<ToolSummary> listTools() {
    return BuiltinTools.describeOnly().stream()
        .map(tool -> new ToolSummary(tool.getName(), tool.getDescription()))
        .sorted((a, b) -> a.name().compareTo(b.name()))
        .toList();
  }

  @Override
  public StatusReport status() {
    String url = jdbcUrl();
    String file = url.startsWith("jdbc:sqlite:") ? url.substring("jdbc:sqlite:".length()) : url;
    boolean exists = url.startsWith("jdbc:sqlite:") && Files.exists(Path.of(file));
    return new StatusReport(VERSION, profileDirectory(), listProfiles().size(), file, exists);
  }

  @Override
  public synchronized String chat(String userId, String profileName, String message) {
    if (context == null) {
      SpringApplicationBuilder builder =
          new SpringApplicationBuilder(OryxOsApplication.class)
              .web(WebApplicationType.NONE)
              .bannerMode(org.springframework.boot.Banner.Mode.OFF);
      // Command-line arguments have the highest precedence; builder.properties() would lose to
      // application.yaml. A one-shot command must not start schedules or print chatty logs.
      List<String> arguments = new ArrayList<>();
      arguments.add("--oryxos.scheduler.enabled=false");
      arguments.add("--logging.level.root=ERROR");
      overrides.forEach((key, value) -> arguments.add("--" + key + "=" + value));
      // The CLI reports a failed startup itself, as one line with the root cause.
      arguments.add("--logging.level.org.springframework.boot.SpringApplication=OFF");
      try {
        context = builder.run(arguments.toArray(String[]::new));
      } catch (RuntimeException startupFailure) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(startupFailure);
        throw new IllegalStateException("Startup failed: " + cause.getMessage(), startupFailure);
      }
    }
    AgentService agent = context.getBean("springAiAgentService", AgentService.class);
    return agent.process("cli", userId, profileName, message);
  }

  @Override
  public void serve(List<String> springArguments) {
    SpringApplication.run(OryxOsApplication.class, springArguments.toArray(String[]::new));
  }

  @Override
  public synchronized void close() {
    if (context != null) {
      context.close();
      context = null;
    }
  }
}
