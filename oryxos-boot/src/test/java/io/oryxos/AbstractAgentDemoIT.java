package io.oryxos;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.DemoSupport.GithubApi;
import io.oryxos.DemoSupport.NewsApi;
import io.oryxos.DemoSupport.Receiver;
import io.oryxos.DemoSupport.WeatherApi;
import io.oryxos.LocalTestServers.FakeModelServer;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One running application with all three lesson-31 agents installed from {@code examples/agents},
 * shared by the scheduler, audit and isolation tests (Spring caches the context, so it starts
 * once). Their cron expressions are far in the future; a test that wants a real cron firing moves
 * one at run time through the REST API. Only the outside world is replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractAgentDemoIT {
  static final String WEATHER_TASK = "weather-daily.morning";
  static final String TECH_TASK = "daily-tech-digest.morning";
  static final String GITHUB_TASK = "github-daily.morning";

  static final String PYTHON = GitHubTrendingScriptTest.findPython();
  static final E2eWorkspace WS = new E2eWorkspace("oryxos-c5-demos");
  static final WeatherApi WEATHER = new WeatherApi();
  static final NewsApi NEWS = new NewsApi();
  static final GithubApi GITHUB = new GithubApi();
  static final Receiver GROUP = new Receiver();
  static final FakeModelServer MODEL = new FakeModelServer(DemoSupport::brain, 0);

  static {
    DemoSupport.installWeather(WS, GROUP.url(), WEATHER.forecastUrl(), "0 0 8 1 1 *");
    DemoSupport.installTech(WS, GROUP.url(), NEWS.searchUrl(), "0 0 9 1 1 *");
    DemoSupport.installGithub(WS, GROUP.url(), GITHUB.baseUrl(), PYTHON, "0 30 9 1 1 *");
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    WS.register(
        registry,
        MODEL.baseUrl(),
        Map.of(
            "oryxos.sandbox.hosts",
            "127.0.0.1,api.open-meteo.com,hn.algolia.com",
            "oryxos.sandbox.read-roots",
            DemoSupport.slash(WS.allowed) + "," + DemoSupport.slash(WS.agents),
            "oryxos.sandbox.commands",
            PYTHON == null ? "python" : PYTHON,
            "oryxos.agents-watch",
            "false"));
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetFixtures() {
    WEATHER.reset();
    NEWS.reset();
    GITHUB.reset();
    GROUP.reset();
    MODEL.reset();
  }

  static void requirePython() {
    assumeTrue(PYTHON != null, "no Python interpreter on PATH");
  }

  int count(String fromWhere) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Integer.class);
    return n == null ? 0 : n;
  }
}
