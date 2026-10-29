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
 * browser from driving the API cross-site, and so must the {@code Host} header (DNS rebinding).
 * This is a stop-gap, not authentication.
 */
final class LocalAccess {
  private static final Pattern IP_LITERAL = Pattern.compile("[0-9.]+|[0-9a-fA-F:]*:[0-9a-fA-F:.]*");

  private LocalAccess() {}

  static void require(HttpServletRequest request) {
    if (!isLoopback(request.getRemoteAddr())
        || request.getHeader("X-Forwarded-For") != null
        || request.getHeader("Forwarded") != null
        || !originIsLocal(request.getHeader("Origin"))
        || !hostIsLocal(request.getHeader("Host"))) {
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
    return origin == null || uriHostIsLocal(origin);
  }

  /**
   * The {@code Host} header must name this machine too. Without it, a web page could rebind its own
   * domain name to 127.0.0.1 and read the API as "same origin", which sends no {@code Origin}.
   */
  private static boolean hostIsLocal(String hostHeader) {
    return hostHeader == null || uriHostIsLocal("http://" + hostHeader);
  }

  private static boolean uriHostIsLocal(String uri) {
    try {
      String host = URI.create(uri).getHost();
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
