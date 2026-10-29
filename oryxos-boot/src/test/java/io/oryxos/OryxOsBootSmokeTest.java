package io.oryxos;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** Uses Spring's full application context to verify wiring across Maven modules. */
@SpringBootTest
@AutoConfigureMockMvc
class OryxOsBootSmokeTest {
  @Autowired private MockMvc mvc;

  @Autowired private ApplicationContext applicationContext;

  @Test
  void prometheusRegistryIsPresentInBootContext() {
    assertFalse(
        applicationContext.getBeansOfType(PrometheusMeterRegistry.class).isEmpty(),
        "Missing PrometheusMeterRegistry: inspect dependency:tree and auto-configuration");
  }

  @Test
  void appHealthUsesSingleResponseEnvelope() throws Exception {
    mvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ok"))
        .andExpect(header().exists("X-Trace-Id"));
  }

  @Test
  void actuatorHealthAndPrometheusAreReachable() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_")));
  }

  @Test
  void openApiContractIsExposed() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").exists());
  }
}
