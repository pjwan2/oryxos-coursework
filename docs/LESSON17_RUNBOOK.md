# Lesson 17 — ReAct control-flow slice (v1.4)

## Status / limitations

This is **Phase 1** of lesson 17: the JDK-only ReAct scheduling/Tool port and its tests. It is deliberately not an end-to-end Spring AI Agent yet. No Session JPA, `ProfileContext`, CLI Picocli, prompt bootstrap loader, tool invocation audit, web chat endpoint, memory or real model calls are claimed.

The parent project is copied from the user's already Maven-verified Gate 2A v1.3. Keep v1.3 as a rollback copy.

## Run on Windows Git Bash (parent pom.xml directory)

```bash
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
java -cp 'oryxos-core/target/classes;oryxos-tool/target/classes;oryxos-cli/target/classes' io.oryxos.cli.ReActLabMain
```

Expected demo outline:

```text
Final: 查询完成：TOOL_RESULT call-1 get_payment P1001 PENDING 120 AUD
USER: 查询支付单 P1001
ASSISTANT: TOOL_CALL call-1 get_payment
TOOL: TOOL_RESULT call-1 get_payment P1001 PENDING 120 AUD
ASSISTANT: 查询完成：...
```

## Verify with the course document

- `oryxos-core` has `ReActLoop` (scheduler), `PromptBuilder` (date/history), and `ModelGateway` / `AgentToolExecutor` ports.
- `oryxos-tool` contains the deliberately read-only allowlisted demo executor.
- `oryxos-cli` adds `ReActLabMain`, **not** the course's Picocli `oryxos chat`.
- Fast JUnit checks: final answer one round, tool result in the next prompt, max iterations, prompt truncation, profile mismatch, tool allowlisting, tool failures.

## Next Phase (mandatory for complete Lesson 17)

1. Bridge `ModelGateway` to **actual Spring AI 1.1.4** `ProviderService`; preserve AssistantMessage tool calls and ToolResponseMessage in the next prompt.
2. Write `tool_invocations` SQLite schema + JPA repository (success/failure) and secure error mapping.
3. Build `AgentService`, `ProfileContext` try/finally cleanup, `ContextLoader` for bootstrap/skills; persist Session.
4. Wire full mock-driven `@SpringBootTest` E2E without API key, then optional real model smoke after consent.
5. DO NOT weaken test or SpotBugs gates to pass Maven.
