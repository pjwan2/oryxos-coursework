package io.oryxos.provider.agent;

import io.oryxos.core.agent.AgentMessage;
import io.oryxos.core.agent.ModelGateway;
import io.oryxos.core.agent.ModelTurn;
import io.oryxos.core.agent.SessionIds;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentDraft;
import io.oryxos.core.profile.AgentDrafting;
import io.oryxos.core.profile.AgentFile;
import io.oryxos.core.profile.AgentProfile;
import io.oryxos.core.profile.DraftUnavailableException;
import io.oryxos.core.workspace.WorkspacePathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the agent's own model for a first draft of its files. The draft goes back to a person, who
 * reads, edits and confirms it; nothing here writes a file, registers an agent or runs anything the
 * model produced (scripts are not even accepted as draft file types). The call goes through the
 * normal {@link ModelGateway}, so it is audited in {@code llm_calls} like any other model call. The
 * prompt and the provider's raw answer are never returned: only the draft files and plain-language
 * problems.
 */
public final class AgentDraftService implements AgentDrafting {
  static final int MAX_DESCRIPTION = 1000;
  static final int MAX_DRAFT_FILES = 5;
  static final int MAX_DRAFT_BYTES = 64 * 1024;
  private static final String CALLER = "admin";
  private static final String PURPOSE = "generator";
  private static final Pattern BLOCK =
      Pattern.compile("<<<FILE ([^>\\r\\n]+)>>>\\r?\\n(.*?)\\r?\\n<<<END>>>", Pattern.DOTALL);
  private static final Pattern FENCE =
      Pattern.compile("^```[a-zA-Z]*\\r?\\n(.*)\\r?\\n```\\s*$", Pattern.DOTALL);
  private static final Set<String> DRAFT_TYPES = Set.of("md");

  private final AgentLifecycleService lifecycle;
  private final ModelGateway gateway;
  private final Supplier<Set<String>> providers;
  private final Supplier<Set<String>> tools;
  private final io.oryxos.provider.ProfileRegistry registry;

  /**
   * @param providers names of the providers that are actually configured (with a usable key)
   * @param tools names of the installed tools the draft may list
   */
  public AgentDraftService(
      AgentLifecycleService lifecycle,
      io.oryxos.provider.ProfileRegistry registry,
      ModelGateway gateway,
      Supplier<Set<String>> providers,
      Supplier<Set<String>> tools) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.providers = Objects.requireNonNull(providers, "providers");
    this.tools = Objects.requireNonNull(tools, "tools");
  }

  @Override
  public AgentDraft generate(String name, String description) {
    if (lifecycle.definition(name).isEmpty()) {
      if (registry.exists(name)) {
        throw new io.oryxos.core.profile.AgentConflictException(
            "Agent '" + name + "' is a read-only legacy profile");
      }
      throw new io.oryxos.core.agent.ProfileNotFoundException(name);
    }
    String wanted = description == null ? "" : description.strip();
    if (wanted.isEmpty() || wanted.length() > MAX_DESCRIPTION) {
      throw new AgentDefinitionException(
          "description is required and must be at most " + MAX_DESCRIPTION + " characters");
    }
    AgentProfile profile = registry.get(name);
    Set<String> configured = providers.get();
    if (profile.provider().model().isBlank() || !configured.contains(profile.provider().name())) {
      throw new DraftUnavailableException(
          "No usable provider/model is configured for this agent (provider '"
              + profile.provider().name()
              + "'; configured: "
              + new TreeSet<>(configured)
              + "). Configure one and try again.");
    }
    ModelTurn answer =
        gateway.chat(
            SessionIds.of(CALLER, PURPOSE, name),
            profile,
            List.of(
                new AgentMessage(AgentMessage.Role.SYSTEM, instructions(profile)),
                new AgentMessage(
                    AgentMessage.Role.USER,
                    "Agent name: " + name + "\nWhat it should do: " + wanted)),
            List.of());
    return check(name, answer.text());
  }

  private String instructions(AgentProfile profile) {
    return """
        You write the definition of an AI agent for the OryxOS runtime as an AGENT.md file.
        Answer with the file only, in exactly this shape:

        <<<FILE AGENT.md>>>
        ---
        name: <the agent name you are given>
        description: <one line>
        provider: {name: @PROVIDER@, model: @MODEL@, temperature: 0.2}
        tools: [<zero or more of the allowed tools>]
        ---
        <the agent's business instructions in Markdown>
        <<<END>>>

        You may add up to four more blocks of the same shape for REFERENCE.md or skills/<name>.md.
        Rules: frontmatter fields may only be name, description, provider, tools, skills,
        notify_channels, schedules, settings. Allowed tools: @TOOLS@. Do not invent tools. Do not write
        scripts, secrets, tokens or URLs with credentials. Plain text only.
        """
        .replace("@PROVIDER@", profile.provider().name())
        .replace("@MODEL@", profile.provider().model())
        .replace("@TOOLS@", new TreeSet<>(tools.get()).toString());
  }

  /** Splits the answer into files and validates them; never throws for a bad draft. */
  AgentDraft check(String name, String answer) {
    List<AgentFile> files = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    Map<String, String> found = new LinkedHashMap<>();
    Matcher blocks = BLOCK.matcher(answer);
    while (blocks.find()) {
      found.putIfAbsent(blocks.group(1).strip(), blocks.group(2));
    }
    if (found.isEmpty()) {
      Matcher fenced = FENCE.matcher(answer.strip());
      String text = fenced.matches() ? fenced.group(1) : answer.strip();
      if (text.startsWith("---")) {
        found.put(AgentLoader.FILE_NAME, text);
      }
    }
    if (found.isEmpty()) {
      problems.add("The model did not return an AGENT.md. Write it by hand or try again.");
      return new AgentDraft(files, false, problems);
    }
    if (found.size() > MAX_DRAFT_FILES) {
      problems.add(
          "The model returned more than " + MAX_DRAFT_FILES + " files; extra files were dropped.");
    }
    for (Map.Entry<String, String> entry : found.entrySet()) {
      if (files.size() >= MAX_DRAFT_FILES) {
        break;
      }
      try {
        List<String> segments = WorkspacePaths.segments(entry.getKey());
        String leaf = segments.getLast();
        if (!DRAFT_TYPES.contains(WorkspacePaths.extension(leaf))) {
          problems.add(
              "Only Markdown files can be drafted; '" + entry.getKey() + "' was left out.");
          continue;
        }
        if (entry.getValue().length() > MAX_DRAFT_BYTES) {
          problems.add("'" + entry.getKey() + "' is too large and was left out.");
          continue;
        }
        String text = entry.getValue();
        files.add(
            new AgentFile(String.join("/", segments), text.endsWith("\n") ? text : text + "\n"));
      } catch (WorkspacePathException refused) {
        problems.add("A file name in the draft is not allowed (" + refused.getMessage() + ").");
      }
    }
    AgentFile agentMd =
        files.stream().filter(f -> AgentLoader.FILE_NAME.equals(f.path())).findFirst().orElse(null);
    if (agentMd == null) {
      problems.add("The draft has no AGENT.md.");
      return new AgentDraft(files, false, problems);
    }
    List<String> resources =
        files.stream().map(AgentFile::path).filter(p -> !AgentLoader.FILE_NAME.equals(p)).toList();
    try {
      Path dir = lifecycle.directoryOf(name);
      lifecycle.loader().parse(name, agentMd.content(), dir, resources);
    } catch (AgentDefinitionException invalid) {
      problems.add(invalid.getMessage());
    }
    return new AgentDraft(files, problems.isEmpty(), problems);
  }
}
