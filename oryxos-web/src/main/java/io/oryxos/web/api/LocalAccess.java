package io.oryxos.web.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Management writes are only for the machine the application runs on until real authentication
 * exists. The peer address must be loopback, the request must not have passed through a proxy
 * (forwarding headers present means the peer address is the proxy's, not the caller's), and a
 * browser {@code Origin}, if any, must itself be local, which stops a web page opened in the user's
 * browser from driving the API cross-site. This is a stop-gap, not authentication.
 */
final class LocalAccess {
  private static final Pattern IP_LITERAL = Pattern.compile("[0-9.]+|[0-9a-fA-F:]*:[0-9a-fA-F:.]*");

  private LocalAccess() {}

  static void require(HttpServletRequest request) {
    if (!isLoopback(request.getRemoteAddr())
        || request.getHeader("X-Forwarded-For") != null
        || request.getHeader("Forwarded") != null
        || !originIsLocal(request.getHeader("Origin"))) {
      throw new LocalAccessOnlyException();
    }
  }

  private static boolean isLoopback(String address) {
    if (address == null || address.isBlank()) {
      return false;
    }
    try {
      return InetAddress.getByName(address).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static boolean originIsLocal(String origin) {
    if (origin == null) {
      return true;
    }
    try {
      String host = URI.create(origin).getHost();
      if (host == null) {
        return false;
      }
      String bare =
          host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
      if ("localhost".equalsIgnoreCase(bare)) {
        return true;
      }
      // Only IP literals are resolved here, so no DNS lookup can happen for an arbitrary name.
      return IP_LITERAL.matcher(bare).matches() && isLoopback(bare);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
