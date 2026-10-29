package io.oryxos.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict parsing of the model-supplied arguments object. */
final class ToolArgs {
  static final ObjectMapper MAPPER = new ObjectMapper();

  private ToolArgs() {}

  static JsonNode parse(String json) {
    try {
      JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("arguments must be a JSON object");
      }
      return node;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("arguments are not valid JSON", e);
    }
  }

  static String requireText(JsonNode args, String field) {
    JsonNode value = args.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.asText();
  }
}
