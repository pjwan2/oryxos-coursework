package io.oryxos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.DemoSupport.GithubApi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The trusted script on its own, against a local GitHub fixture: what it asks GitHub for, how it
 * merges, and how it fails. (It runs real Python; without an interpreter these tests are skipped
 * and reported as skipped.)
 */
class GitHubTrendingScriptTest {
  static final Path SCRIPT =
      DemoSupport.EXAMPLES.resolve("github-daily/scripts/github_trending.py");
  private static final GithubApi GITHUB = new GithubApi();
  private static final String PYTHON = findPython();

  record Result(int exit, String stdout) {
    JsonNode json() {
      try {
        return LocalTestServers.JSON.readTree(stdout);
      } catch (IOException e) {
        throw new AssertionError("stdout is not JSON: " + stdout, e);
      }
    }
  }

  static String findPython() {
    for (String candidate : List.of("python", "python3")) {
      try {
        Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
          return candidate;
        }
      } catch (IOException | InterruptedException e) {
        // try the next name
      }
    }
    return null;
  }

  @AfterAll
  static void stop() {
    GITHUB.close();
  }

  @BeforeEach
  void reset() {
    assumeTrue(PYTHON != null, "no Python interpreter on PATH");
    GITHUB.reset();
  }

  static Result run(String... args) {
    List<String> command = new ArrayList<>(List.of(PYTHON, DemoSupport.slash(SCRIPT)));
    command.addAll(List.of(args));
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      Thread t =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      p.getErrorStream().transferTo(err);
                    } catch (IOException ignored) {
                      // diagnostics only
                    }
                  });
      p.getInputStream().transferTo(out);
      assertTrue(p.waitFor(60, TimeUnit.SECONDS), "script timed out");
      t.join(2000);
      assertEquals(
          "",
          err.toString(StandardCharsets.UTF_8).strip().replaceAll("\\s+", " "),
          "nothing is written to stderr");
      return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8));
    } catch (IOException | InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  private static Result okRun(String... extra) {
    List<String> args =
        new ArrayList<>(List.of("--api-base", GITHUB.baseUrl(), "--today", "2026-03-05"));
    args.addAll(List.of(extra));
    return run(args.toArray(String[]::new));
  }

  @Test
  void itPrintsOneJsonObjectWithTodayMonthAndAi() {
    Result result = okRun("--limit", "5");

    assertEquals(0, result.exit());
    JsonNode json = result.json();
    assertEquals("2026-03-05", json.get("generated_for").asText());
    assertEquals(3, json.get("today").size());
    assertEquals("acme/fresh-db", json.at("/today/0/name").asText());
    assertEquals(910, json.at("/today/0/stars").asInt());
    assertEquals("https://github.com/acme/fresh-db", json.at("/today/0/url").asText());
    assertEquals("A brand new embedded database", json.at("/today/0/description").asText());
    assertEquals("acme/big-month", json.at("/month/0/name").asText());
    assertFalse(json.at("/meta/empty").asBoolean());
    assertEquals(1, result.stdout().strip().split("\n").length, "exactly one line of JSON");
  }

  @Test
  void itSaysItIsNotTheOfficialTrendingList() {
    JsonNode json = okRun().json();

    assertTrue(json.get("source").asText().contains("NOT the official GitHub Trending"));
    assertTrue(json.at("/windows/today").asText().contains("created on or after 2026-03-04"));
    assertTrue(json.at("/windows/month").asText().contains("created on or after 2026-02-03"));
  }

  @Test
  void theWindowsAreCreatedDateFiltersSortedByStars() {
    okRun("--limit", "5");

    List<String> queries = GITHUB.queries();
    assertTrue(
        queries
            .getFirst()
            .startsWith("created:>=2026-03-04 | sort=stars order=desc per_page=10 page=1"),
        queries.getFirst());
    assertTrue(
        queries.get(1).startsWith("created:>=2026-02-03 | sort=stars order=desc"), queries.get(1));
  }

  @Test
  void eachAiTopicIsItsOwnQueryNotOneAndedQuery() {
    JsonNode ai = okRun("--limit", "5").json().get("ai");

    List<String> topicQueries =
        GITHUB.queries().stream().filter(q -> q.contains("topic:")).toList();
    assertEquals(5, topicQueries.size());
    for (String q : topicQueries) {
      assertEquals(1, q.split("topic:", -1).length - 1, "one topic per query: " + q);
    }
    assertEquals(
        List.of("lab/ai-agents", "lab/llm-kit", "lab/rag-engine", "lab/ml-toolbox"),
        LocalTestServers.JSON.convertValue(ai.findValuesAsText("name"), List.class),
        "merged across topics, duplicates removed, stars descending");
  }

  @Test
  void theLimitCapsEveryListAndAnOutOfRangeLimitIsRefused() {
    JsonNode json = okRun("--limit", "2").json();
    assertEquals(2, json.get("today").size());
    assertEquals(2, json.get("month").size());
    assertEquals(2, json.get("ai").size());

    for (String bad : new String[] {"0", "11", "abc", "-1"}) {
      Result refused = run("--api-base", GITHUB.baseUrl(), "--limit", bad);
      assertEquals(5, refused.exit(), bad);
      assertEquals(5, refused.json().get("code").asInt());
    }
  }

  @Test
  void anEmptyAnswerIsAnEmptyReportNotAnError() {
    GITHUB.mode("empty");

    Result result = okRun();

    assertEquals(0, result.exit());
    assertEquals(0, result.json().get("today").size());
    assertTrue(result.json().at("/meta/empty").asBoolean());
  }

  @Test
  void aRateLimitIsReportedWithExitCode3() {
    GITHUB.mode("ratelimit");

    Result result = okRun();

    assertEquals(3, result.exit());
    assertTrue(result.json().get("error").asText().contains("rate limit"));
    assertEquals(1, GITHUB.queries().size(), "no retry loop");
  }

  @Test
  void aServerErrorAnUnparseableAnswerAndAnOversizedAnswerAreExitCode4() {
    for (String mode : new String[] {"error", "notjson", "huge"}) {
      GITHUB.reset();
      GITHUB.mode(mode);
      Result result = okRun();
      assertEquals(4, result.exit(), mode);
      assertTrue(result.json().has("error"), mode);
    }
  }

  @Test
  void anUnreachableServerIsExitCode2() {
    Result result = run("--api-base", "http://127.0.0.1:9", "--today", "2026-03-05");
    assertEquals(2, result.exit());
    assertTrue(result.json().get("error").asText().startsWith("network error"));
  }

  @Test
  void anApiBaseWithCredentialsOrAnotherSchemeIsRefused() {
    for (String bad :
        new String[] {
          "ftp://example.org", "http://user:pass@127.0.0.1:1", "file:///etc/passwd", "not a url"
        }) {
      Result result = run("--api-base", bad);
      assertEquals(5, result.exit(), bad);
      assertFalse(result.stdout().contains("pass@"), "credentials are never echoed");
    }
  }

  @Test
  void aBadDateIsRefused() {
    assertEquals(5, run("--api-base", GITHUB.baseUrl(), "--today", "yesterday").exit());
  }

  @Test
  void textFromGithubIsShortenedAndStrippedOfControlCharacters() {
    GITHUB.mode("dirty");

    JsonNode json = okRun("--limit", "3").json();

    String description = json.at("/today/0/description").asText();
    assertTrue(description.length() <= 200, "shortened: " + description.length());
    assertFalse(
        description.matches("(?s).*[\\x00-\\x1f\\x7f].*"), "no control characters, no line breaks");
    assertTrue(description.startsWith("first line second line"));
  }
}
