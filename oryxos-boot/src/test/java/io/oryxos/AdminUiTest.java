package io.oryxos;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Vue console is served from the same application under /admin/: the entry page, client-side
 * routes (reload / deep link), real assets, and no interference with /api/**. Needs the frontend
 * build output on the classpath (npm run build), which the oryxos-web module insists on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUiTest {
  private static final ScheduleTestEnvironment ENV = environment();

  private static ScheduleTestEnvironment environment() {
    try {
      return new ScheduleTestEnvironment(Files.createTempDirectory("oryxos-admin-ui"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    registry.add("oryxos.profile-directory", () -> ScheduleTestEnvironment.slash(ENV.profiles));
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + ScheduleTestEnvironment.slash(ENV.database));
    registry.add(
        "oryxos.memory.markdown-path",
        () -> ScheduleTestEnvironment.slash(ENV.root.resolve("MEMORY.md")));
    registry.add("oryxos.sandbox.read-roots", () -> ScheduleTestEnvironment.slash(ENV.allowed));
    registry.add("oryxos.sandbox.write-roots", () -> ScheduleTestEnvironment.slash(ENV.allowed));
  }

  @Autowired MockMvc mvc;

  @Test
  void adminRedirectsToTheTrailingSlashUrl() throws Exception {
    mvc.perform(get("/admin"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/"));
  }

  @Test
  void theEntryPageIsTheViteBuildUnderTheAdminBase() throws Exception {
    mvc.perform(get("/admin/"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(content().string(containsString("<div id=\"app\"></div>")))
        .andExpect(content().string(containsString("/admin/assets/")))
        .andExpect(header().string("Cache-Control", containsString("no-cache")));
    mvc.perform(get("/admin/index.html")).andExpect(status().isOk());
  }

  @Test
  void everyClientRouteAndDeepLinkReloadsTheAppShellNot404() throws Exception {
    for (String route :
        new String[] {
          "/admin/sessions",
          "/admin/sessions/web:u1:demo",
          "/admin/profiles",
          "/admin/tools",
          "/admin/memory",
          "/admin/system",
          "/admin/scheduler",
          "/admin/some/unknown/route"
        }) {
      mvc.perform(get(route))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith("text/html"))
          .andExpect(content().string(containsString("<div id=\"app\"></div>")));
    }
  }

  @Test
  void realAssetsAreServedWithTheirOwnContentTypeAndMissingOnesStay404() throws Exception {
    String index = mvc.perform(get("/admin/")).andReturn().getResponse().getContentAsString();
    Matcher script = Pattern.compile("src=\"(/admin/assets/[^\"]+\\.js)\"").matcher(index);
    Matcher style = Pattern.compile("href=\"(/admin/assets/[^\"]+\\.css)\"").matcher(index);
    assertTrue(script.find() && style.find(), "index.html references a script and a stylesheet");

    mvc.perform(get(script.group(1)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/javascript"))
        .andExpect(content().string(not(containsString("<div id=\"app\"></div>"))));
    mvc.perform(get(style.group(1)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/css"));

    mvc.perform(get("/admin/assets/does-not-exist.js")).andExpect(status().isNotFound());
    mvc.perform(get("/admin/missing.png")).andExpect(status().isNotFound());
  }

  @Test
  void apiRoutesAreNotCapturedByTheSpaFallback() throws Exception {
    mvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ok"));
    mvc.perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    // An unknown API path is a JSON 404, never the HTML shell.
    mvc.perform(get("/api/v1/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.code").value(404));
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
  }

  @Test
  void pathTricksCannotReadFilesOutsideTheConsole() throws Exception {
    mvc.perform(get("/admin/..%2f..%2fapplication.yaml")).andExpect(status().is4xxClientError());
    mvc.perform(get("/admin/%2e%2e/%2e%2e/application.yaml"))
        .andExpect(status().is4xxClientError());
  }
}
