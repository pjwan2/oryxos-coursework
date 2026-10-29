package io.oryxos.tool.notify;

import java.util.Map;
import java.util.Objects;

/** A named destination from a Profile's notify_channels, e.g. {name: ops, type: webhook, url}. */
public record NotifyTarget(String name, String type, String url) {
  public NotifyTarget {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(url, "url");
    if (name.isBlank() || type.isBlank() || url.isBlank()) {
      throw new IllegalArgumentException("Notify target needs name, type and url");
    }
  }

  public static NotifyTarget from(Map<String, Object> entry) {
    return new NotifyTarget(
        String.valueOf(entry.getOrDefault("name", "")),
        String.valueOf(entry.getOrDefault("type", "")),
        String.valueOf(entry.getOrDefault("url", "")));
  }
}
