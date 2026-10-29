package io.oryxos.web.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * {@code /admin/} itself. Spring's resource handler is never asked for an empty path, so the entry
 * page of the console is answered here; every other {@code /admin/**} path goes through {@link
 * AdminUiConfiguration}.
 */
@Controller
public class AdminIndexController {
  private static final Resource INDEX = new ClassPathResource("static/admin/index.html");

  @GetMapping(value = "/admin/", produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public ResponseEntity<Resource> index() {
    if (!INDEX.exists()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .contentType(MediaType.TEXT_HTML)
        .body(INDEX);
  }
}
