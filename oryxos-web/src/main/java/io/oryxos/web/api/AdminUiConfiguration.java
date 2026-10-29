package io.oryxos.web.api;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Vue admin console from the same JAR under {@code /admin/}. Only that prefix is
 * touched, so {@code /api/**} keeps its own handlers. A path without a file extension that matches
 * no file is a client-side route of the single-page app (e.g. {@code /admin/scheduler}) and gets
 * {@code index.html}; a missing file such as {@code /admin/assets/x.js} stays a plain 404.
 */
@Configuration
public class AdminUiConfiguration implements WebMvcConfigurer {
  static final String LOCATION = "classpath:/static/admin/";

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations(LOCATION)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(new SpaFallbackResolver());
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/admin", "/admin/");
  }

  /** Real files first; otherwise extension-less paths fall back to the app shell. */
  static final class SpaFallbackResolver extends PathResourceResolver {
    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource file = super.getResource(resourcePath, location);
      if (file != null) {
        return file;
      }
      boolean looksLikeFile =
          resourcePath.substring(resourcePath.lastIndexOf('/') + 1).contains(".");
      return looksLikeFile ? null : super.getResource("index.html", location);
    }
  }
}
