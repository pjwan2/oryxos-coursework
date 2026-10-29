# Module boundaries

Nine Maven modules, one composition root. The rules below are checked on every build by `oryxos-boot/src/test/java/io/oryxos/ArchitectureRulesTest.java` (8 tests); the first column of the rule table says which test.

## Dependency graph (compile scope)

```
                              oryxos-boot   (composition root, runnable JAR)
        ____________________________|_______________________________________________
       |        |         |          |          |            |          |           |
   oryxos-   oryxos-   oryxos-    oryxos-    oryxos-      oryxos-    oryxos-     oryxos-
    cli       web      tool      memory    scheduler    provider    storage       core
     |          |        |          |          |            |         |   \         ^
     +----+     |        |          |          |            |         |    \        |
          v     v        v          v          v            v         v     v       |
        tool   core      core      core       core         core      core  provider  |
        core                                                                 +-------+
```

Written as a table (this is what the test asserts, exactly):

| Module | May depend on (compile scope) |
|---|---|
| `oryxos-core` | nothing but the JDK |
| `oryxos-provider` | core |
| `oryxos-tool` | core |
| `oryxos-memory` | core (storage only in test scope, for `db/schema.sql`) |
| `oryxos-storage` | core, provider |
| `oryxos-scheduler` | core |
| `oryxos-cli` | core, tool |
| `oryxos-web` | core |
| `oryxos-boot` | all of the above |

No cycles. Every arrow points toward `core`, which holds the contracts.

## What each module owns

| Module | Owns | Main classes |
|---|---|---|
| **core** | The domain and the ports: agent orchestration, session model, tool contract, memory contract, sandbox contract, schedule contract, profile record. No framework, no I/O. | `AgentService`, `ReActLoop`, `PromptBuilder`, `AgentSession`, `SessionIds`, `AgentSessionStore` (port), `ModelGateway` (port), `AgentToolExecutor` (port), `ToolCallAudit`/`ToolCallHistory` (ports), `OryxTool`, `ToolRegistry`, `LongTermMemoryStore` (port), `Sandbox` (port), `SandboxSpec`, `ScheduleService`/`ScheduledTaskStore` (ports), `AgentProfile`, `ProfileCatalog`, `SafeErrorText` |
| **provider** | Turning a Profile's provider name into a Spring AI `ChatModel` call, with tool execution switched off; YAML profile loading; LLM-call audit port; classification of provider failures. | `ProviderConfiguration`, `ProviderService`, `SpringAiModelGateway`, `ModelFailures`, `ProfileLoader`, `ProfileRegistry`, `MockChatModel` |
| **tool** | Built-in tools and the only code that executes tools: sandbox enforcement, registry-based executor, audit wrapper, MCP client, notify. | `RegistryToolExecutor`, `AuditedToolExecutor`, `ProfileSandbox`, `AllowListSandbox`, `SandboxPolicy`, `FileReadTool`, `FileWriteTool`, `HttpGetTool`, `ShellTool`, `NotifyTool`, `McpClient` |
| **memory** | Long-term memory backends behind one interface, the save/recall tools and the prompt loaders. | `MarkdownMemoryStore`, `SqliteMemoryStore`, `Mem0MemoryStore`, `MemoryService`, `SaveMemoryTool`, `RecallMemoryTool`, `MemoryContextLoader`, `BootstrapContextLoader` |
| **storage** | All relational persistence: JPA entities for sessions and audits, the JDBC scheduler store, schema and migrations. | `JpaAgentSessionStore`, `JpaLlmCallAudit`, `JpaToolCallAudit`, `JpaToolCallHistory`, `JdbcScheduledTaskStore`, `SqliteMigrator`, `Migrations`, `db/schema.sql` |
| **scheduler** | Cron triggering, time zones, overlap protection, run recording. Runs agents only through `AgentService`. | `AgentScheduler`, `ScheduleConfigParser`, `ScheduleConfig` |
| **cli** | The Picocli command line. | `OryxCli`, `CliRuntime` |
| **web** | REST controllers, the response envelope, exception mapping, local-access rule, serving the admin console. Controllers only validate, call a service/port and map the answer. | `SessionApiController`, `AgentApiController`, `ProfileApiController`, `MemoryApiController`, `ToolApiController`, `SystemApiController`, `ScheduleApiController`, `AgentInvoker`, `GlobalExceptionHandler`, `LocalAccess`, `AdminUiConfiguration` |
| **boot** | Wiring only: Spring Boot application, configuration classes, `application.yaml`, the integration tests that need the whole system. | `OryxOsApplication`, `Lesson17RuntimeConfiguration`, `ToolConfiguration`, `MemoryConfiguration`, `SchedulerConfiguration`, `BootCliRuntime` |

## Rules and where they are enforced

| # | Rule | How it is enforced |
|---|---|---|
| R1 | Exactly the nine course modules exist | `ArchitectureRulesTest.theNineModulesAreExactlyTheCourseModules` |
| R2 | Module dependencies follow the table above | `...compileScopeDependenciesFollowTheAllowedGraph` (parses the poms) |
| R3 | `core` imports only `java.*` and itself, so it cannot depend on Spring, JPA, HTTP clients or any implementation | `...coreImportsNothingButTheJdkAndItself` |
| R4 | JPA, Hibernate, Spring Data/JDBC and the SQLite driver are used in `storage` only; `java.sql`/`javax.sql` also appear in `SqliteMemoryStore` and in boot's `MemoryConfiguration` (see "Known deviation" below) | `...persistenceApisAreUsedInStorageOnlyExceptTheDocumentedMemoryJdbcStore` |
| R5 | `web` reaches execution only through core ports and `AgentService`: no storage, provider, tool, memory or scheduler imports, and no `ReActLoop`, `AgentToolExecutor` or `ModelGateway` | `...restControllersNeverReachIntoExecutionOrInfrastructureModules` |
| R6 | Provider and Tool execution are separated: `provider` never imports `tool` or `AgentToolExecutor` (it advertises tool schemas, the call has `internalToolExecutionEnabled(false)`); `scheduler` never touches tools, providers or the loop | `...schedulerAndProviderCannotRunToolsOrTheLoopDirectly`; `ProviderServiceTest.providerNamesRouteExactlyAndToolExecutionDisabled` |
| R7 | One tool registry, one session store implementation, one tool-audit writer | `...thereIsOneToolRegistryOneSessionStoreAndOneToolCallAuditWriter` |
| R8 | No path executes a tool without the audit wrapper: every `new RegistryToolExecutor(` / `new DemoToolExecutor(` in production code sits inside `new AuditedToolExecutor(` | `...everyToolExecutorInProductionCodeIsWrappedByTheAudit` |
| R9 | Memory is reached through `LongTermMemoryStore` only; the backend is chosen in one place | `MemoryConfiguration.longTermMemoryStore` (switch on `oryxos.memory.backend`); contract tests run the same assertions on all backends |
| R10 | Sandbox permission comes from the calling Profile, never from a global or a thread-local | `ProfileSandbox.forProfile(...)` is called with `ToolContext.profile()`; `ProfileSandboxTest`, `SandboxEnforcementTest`, `SandboxIntegrationTest` |
| R11 | The scheduler runs agents through the same `AgentService` bean as CLI and REST | `SchedulerConfiguration` injects `springAiAgentService`; `ScheduledTaskE2ETest` checks the session row written by the scheduler |

## Known deviation (documented, not hidden)

**SQLite memory backend lives in `oryxos-memory`, not in `oryxos-storage`.** `SqliteMemoryStore` talks to the database through a plain `javax.sql.DataSource` (no JPA, no driver import), and the `memory_entries` table, its constraint and its migration (V1) are owned by `oryxos-storage`. Moving the class into storage would force `storage` to depend on `memory` (or on a test-jar of its contract tests) and move its 16 tests (14 of them the shared contract) for no behavioural gain, so it was left in place. R4 whitelists exactly this file and `MemoryConfiguration`; anything else using `java.sql` outside storage fails the build.

**`storage` depends on `provider`** because the model-call audit port (`LlmCallAudit`) is declared in the provider module and implemented by `JpaLlmCallAudit`. The direction is acceptable (infrastructure depends on a contract) but the port would sit more naturally in `core`. Candidate cleanup for later; it needs a public-interface move, which this round avoided on purpose.

## Spring bean registration

`@SpringBootApplication` in `io.oryxos` scans every module's `io.oryxos.*` package. Stereotype beans (`@Service`, `@RestController`, `@Component`) live in storage, web and provider; domain objects from core and tool/memory/scheduler are created explicitly in boot's configuration classes so that the wiring is visible in one place:

| Bean | Defined in |
|---|---|
| `ToolRegistry` (the only one), `Sandbox` ceiling | `ToolConfiguration` |
| `LongTermMemoryStore`, `MemoryService`, `SaveMemoryTool`, `RecallMemoryTool`, `ContextLoader`s | `MemoryConfiguration` |
| `PromptBuilder`, `ReActLoop`s, `AgentToolExecutor`, `AgentService` (`springAiAgentService`, plus the lab's `lesson17AgentService`) | `Lesson17RuntimeConfiguration` |
| `AgentScheduler`, `ThreadPoolTaskScheduler` | `SchedulerConfiguration` |
| `ChatModel` map, `ProfileRegistry` (also the `ProfileCatalog`) | `ProviderConfiguration` |

The class name `Lesson17RuntimeConfiguration` is historical; renaming it was judged churn without benefit.

## Tests and where they live

| Kind | Location | Runner |
|---|---|---|
| Unit tests and ports with fakes | each module's `src/test` | surefire |
| Controller slices (MockMvc standalone, mocked ports) | `oryxos-web/src/test` | surefire |
| Whole-system tests (real Spring context, temporary SQLite, Mock model, real tools) | `oryxos-boot/src/test` (`*IntegrationTest`, `*E2ETest`, `AdminUiTest`, `ArchitectureRulesTest`) | surefire |
| Real executable JAR as a separate process | `oryxos-boot/src/test/.../WebSmokeIT.java` | failsafe, after `package` |
| Front-end unit tests | `oryxos-web/src/main/frontend/tests` (vitest) | `npm test` |
