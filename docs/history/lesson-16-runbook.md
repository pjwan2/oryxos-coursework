# Lesson 16 — Provider engineering and verification

This increment builds **on the already-proven Gate 1 v1.1 base**. It is not a completed ReAct agent, CLI or OryxOS release.

## What is implemented

- Model-neutral `AgentProfile` with the course's required config groups and separate global credentials.
- `ProfileLoader` (SafeConstructor / SnakeYAML), invalid YAML isolated, explicit provider validation.
- `ProviderConfiguration` mapping **name → ChatModel**; only `mock` is active unless you set `DEEPSEEK_API_KEY`.
- Spring AI **1.1.4** `ProviderService` issuing exactly one model call, using `.internalToolExecutionEnabled(false)`.
- `ToolSchemaAdapter`: converts name/description/JSON schema into a callback which **refuses to execute**.
- Separate `LlmCallAudit` port; SQLite-backed JPA entity/repository, success and failure records.
- Unittests (routing, tool boundary, audit failure, profile loading) + application integration with real SQLite.

## Windows Git Bash steps

**Stop your currently running Gate 1 server (Ctrl+C in the server terminal).** Do not overwrite Gate 1.

1. Unzip this archive in `~/Downloads/OryxOS_V1/`. If there is an extra nesting layer, use `find . -name pom.xml`.
2. Open the directory containing the **parent pom.xml**.
3. Run:

```bash
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

`BUILD SUCCESS` is the only valid completion claim for the full build. The package author could not run Maven due to unavailable Maven dependencies in the sandbox.

4. Run the actual application (once all tests are green):

```bash
cp -n examples/profiles/demo.yaml .oryxos/profiles/demo.yaml   # creates profile workspace on Git Bash
mvn -B -ntp -pl oryxos-boot -am package -DskipTests
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar --server.port=8085
```

If the `cp` destination does not exist, create it first: `mkdir -p .oryxos/profiles`.

5. Verify existing Gate 1 endpoints stay healthy:

```bash
curl -i http://localhost:8085/api/v1/health
curl -s http://localhost:8085/actuator/prometheus | head
curl -i http://localhost:8085/v3/api-docs
```

### What tests mean

- `ProviderServiceTest` uses a mocked Spring AI `ChatModel`, so there is **no external call or cost**.
- `ProviderBootIntegrationTest` calls the concrete, offline `MockChatModel`, then verifies SQLite audit persisted.
- A real-LLM smoke test still requires an API key and separate manual authorisation; not included in CI.

### Important course limitations

- Lesson 16 diagrams are *illustrative*; verify your actual Spring AI BOM and API, particularly tool-control version differences. Spring AI 2.x removes `internalToolExecutionEnabled`.
- There is **no ReActLoop** wired yet (lesson 17), **no actual tool execution** here (lesson 20).
- This increment uses Spring AI's OpenAI-compatible model adapter. It does **not** claim to implement all Spring AI Alibaba-specific provider integrations.
- SQLite/JPA schemas are created by `schema.sql`, never `ddl-auto=update`.
- Some advanced Profile placeholder resolution and course's full `ProfileLoaderTest` list are still pending.

## If errors occur

Send the FIRST Maven error, the Reactor Summary, and the final 30 lines, not just `BUILD FAILURE`. In case of missing dependencies first inspect `mvn -pl oryxos-provider -am dependency:tree`.
