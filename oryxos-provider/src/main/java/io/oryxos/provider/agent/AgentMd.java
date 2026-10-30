package io.oryxos.provider.agent;

import io.oryxos.core.profile.AgentDefinitionException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Splits an AGENT.md into its YAML frontmatter and its Markdown body. Plain data only. */
final class AgentMd {
  static final int MAX_CHARS = 128 * 1024;
  private static final String FENCE = "---";

  record Parts(Map<String, Object> frontmatter, String body) {
    Parts {
      frontmatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
    }

    @Override
    public Map<String, Object> frontmatter() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
    }
  }

  private AgentMd() {}

  static Parts parse(String raw) {
    if (raw.length() > MAX_CHARS) {
      throw new AgentDefinitionException("AGENT.md is larger than " + MAX_CHARS + " characters");
    }
    String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
    text = text.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = text.split("\n", -1);
    if (lines.length == 0 || !FENCE.equals(lines[0].stripTrailing())) {
      throw new AgentDefinitionException(
          "AGENT.md must start with a line containing only '---' (the YAML frontmatter)");
    }
    int close = -1;
    for (int i = 1; i < lines.length; i++) {
      if (FENCE.equals(lines[i].stripTrailing())) {
        close = i;
        break;
      }
    }
    if (close < 0) {
      throw new AgentDefinitionException(
          "AGENT.md frontmatter is not closed: add a line containing only '---' after it");
    }
    String yamlText = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, close));
    String body = String.join("\n", java.util.Arrays.copyOfRange(lines, close + 1, lines.length));
    return new Parts(frontmatter(yamlText), body.strip());
  }

  private static Map<String, Object> frontmatter(String yamlText) {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit(MAX_CHARS);
    options.setAllowDuplicateKeys(false);
    Object loaded;
    try {
      loaded = new Yaml(new SafeConstructor(options)).load(yamlText);
    } catch (YAMLException | ClassCastException e) {
      String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      int newline = why.indexOf('\n');
      String firstLine = newline < 0 ? why : why.substring(0, newline);
      throw new AgentDefinitionException(
          "AGENT.md frontmatter is not valid YAML: "
              + (firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine));
    }
    if (!(loaded instanceof Map<?, ?> map) || map.isEmpty()) {
      throw new AgentDefinitionException("AGENT.md frontmatter must be a non-empty YAML mapping");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new AgentDefinitionException("AGENT.md frontmatter keys must be text");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }
}
