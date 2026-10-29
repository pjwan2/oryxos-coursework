package io.oryxos.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Give each HTTP request a traceId; JSON logback output includes MDC entries. */
@Component
public class TraceIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = UUID.randomUUID().toString();
    try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
      response.setHeader("X-Trace-Id", traceId);
      filterChain.doFilter(request, response);
    }
  }
}
