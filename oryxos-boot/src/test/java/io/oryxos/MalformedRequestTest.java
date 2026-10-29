package io.oryxos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Client mistakes must be 4xx, never a masked 500. */
@SpringBootTest(properties = "oryxos.profile-directory=../.oryxos/profiles")
@AutoConfigureMockMvc
class MalformedRequestTest {
  private static final String CHAT = "/api/v1/labs/payment/chat";
  @Autowired MockMvc mvc;

  @Test
  void gbkEncodedChineseBodyIsBadRequest() throws Exception {
    byte[] gbk = "{\"userId\":\"u\",\"content\":\"查询支付单 P1001\"}".getBytes(Charset.forName("GBK"));
    mvc.perform(post(CHAT).contentType(MediaType.APPLICATION_JSON).content(gbk))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void brokenJsonAndMissingBodyAreBadRequest() throws Exception {
    mvc.perform(post(CHAT).contentType(MediaType.APPLICATION_JSON).content("{oops"))
        .andExpect(status().isBadRequest());
    mvc.perform(post(CHAT).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  void wrongContentTypeAndMethodAreNot500() throws Exception {
    mvc.perform(post(CHAT).contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(get(CHAT)).andExpect(status().isMethodNotAllowed());
  }

  @Test
  void utf8ChineseBodyStillWorks() throws Exception {
    mvc.perform(
            post(CHAT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\"mvc1\",\"content\":\"查询支付单 P1001\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.containsString("PENDING")));
  }
}
