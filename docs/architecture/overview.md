# Architecture overview

OryxOS is a small Agent runtime: a ReAct loop that talks to a model, calls tools under a per-agent sandbox, remembers things, and can be driven from a command line, an HTTP API or a clock. This page shows how the pieces fit. The rules between modules are in [module-boundaries.md](module-boundaries.md); the reasons for the main choices are in [design-decisions.md](design-decisions.md).

Status: course increment after stage B4 (weeks 1 to 4). It is coursework, not a finished Agent OS, and it has no authentication (see [known limitations](../coursework/week04/known-limitations.md)).

## One engine, three entrances

```
   CLI (Picocli)          REST controllers            AgentScheduler (cron)
   oryxos-cli             oryxos-web                  oryxos-scheduler
   BootCliRuntime         AgentInvoker                AgentScheduler.fire / runNow
        \                      |                           /
         \                     |                          /
          +-------------> AgentService.process(channel, userId, profile, message)
                              (oryxos-core, the only orchestrator bean "springAiAgentService")
                                         |
                    SessionIds.of(channel, userId, profile)  -> "web:alice:ops"
                                         |
                 loads the Session, sets ProfileContext, runs ReActLoop, ALWAYS saves
                                         |
                                    ReActLoop  (max_iterations from the Profile, default 10)
                                    /                \
                 ModelGateway.chat(...)          AgentToolExecutor.execute(...)
                 SpringAiModelGateway            AuditedToolExecutor
                       |                               |
                 ProviderService                RegistryToolExecutor
                 (named ChatModel,                     |
                  tool execution OFF,           ToolRegistry  --  Profile tool allow-list
                  writes llm_calls)                    |
                                                  OryxTool.execute(args, ToolContext(profile))
                                                  sandbox.forProfile(profile).check...(first line)
```

Each entrance only translates its own input into `(channel, userId, profile, message)`:

| Entrance | channel | userId | Where |
|---|---|---|---|
| CLI `chat` | `cli` | `--user`, default `local` | `oryxos-boot/.../BootCliRuntime.java` gets the bean `springAiAgentService` |
| REST sessions | `web` | from the session id | `oryxos-web/.../AgentInvoker.java` (`@Qualifier("springAiAgentService")`) |
| REST agent invoke | `api` | `userId` or `default` | same `AgentInvoker` |
| Scheduler | `scheduler` | `scheduler` | `oryxos-boot/.../SchedulerConfiguration.java` injects the same bean into `AgentScheduler` |

Because the session id is built by one function (`SessionIds`), the same conversation can be continued from any entrance and the scheduler's runs show up in the ordinary session list.

There is one more `AgentService` bean, `lesson17AgentService`, wired to a scripted model (`PaymentScriptedGateway`). It only serves the lesson 17 payment lab endpoints under `/api/v1/labs/payment` and the tests that depend on them. It shares the same session store and the same audited tool executor; it is not a second session or tool system. See [design-decisions.md](design-decisions.md), D-08.

## What is stored where

| Data | Where | Written by |
|---|---|---|
| Conversations (`sessions`) | SQLite, one row per session id, messages as JSON | `JpaAgentSessionStore` (storage) |
| Model calls (`llm_calls`) | SQLite | `JpaLlmCallAudit`, called from `ProviderService` |
| Tool calls (`tool_invocations`), including sandbox refusals | SQLite | `JpaToolCallAudit`, called from `AuditedToolExecutor` |
| Long-term memory | one of: `MEMORY.md`, SQLite `memory_entries`, a Mem0 server | the chosen `LongTermMemoryStore` |
| Scheduled tasks and run history (`scheduled_tasks`, `task_executions`) | SQLite | `JdbcScheduledTaskStore` (storage) |
| Schema version (`schema_migrations`) | SQLite | `SqliteMigrator` (storage) |
| Agent definitions | YAML files in `.oryxos/profiles/` | read once at startup by `ProfileLoader` |

The database schema is owned by `oryxos-storage` (`db/schema.sql` for new files, versioned migrations for old ones). Hibernate never changes it (`ddl-auto: none`).

## How a prompt is built

`PromptBuilder` (core) produces: system text (the Profile's `identity.prompt`, today's date, plus whatever every `ContextLoader` adds) followed by the last 20 session messages. Two loaders exist: `BootstrapContextLoader` (optional files named in the Profile, read through the sandbox) and `MemoryContextLoader` (CORE memory in full, ARCHIVAL memory truncated), the latter only for agents whose Profile lists `save_memory` or `recall_memory`. The session history is not repeated by memory.

## Cross-cutting behaviour

- **Permissions.** A tool can run only if (1) the Profile lists it and (2) the sandbox derived from that Profile allows the specific action. The sandbox is passed explicitly in `ToolContext`, never through a thread-local. Details: [sandbox-design.md](../coursework/week04/sandbox-design.md).
- **Audit.** Every tool attempt (success, failure, refusal) and every model call is a database row. Error text in audit rows is sanitised (`SafeErrorText`): no URLs beyond host, no tokens, at most 200 characters.
- **Errors toward clients.** The REST layer answers with one envelope `{code, message, data, timestamp}`; the 500 text is fixed and provider/exception messages are never returned.
- **Observability.** Every HTTP response has `X-Trace-Id` (also in JSON logs via MDC); `/actuator/prometheus` for metrics; OpenAPI at `/v3/api-docs`.
- **Local only.** The server binds `127.0.0.1`. Session, memory, tool-call, invoke and scheduler-write endpoints additionally refuse requests that are not loopback, are forwarded, or carry a foreign `Origin` or `Host`. This is a stop-gap, not authentication.

## Startup order

1. `DatabaseMigrationConfiguration` backs up an existing database file, rehearses the upgrade on a copy, then applies versioned migrations. If this fails nothing else starts.
2. `ProfileLoader` reads the profile directory; an invalid sandbox declaration stops startup, other invalid profiles are skipped with an error log.
3. Providers, tool registry (built-in tools plus memory tools), session store, memory store.
4. `AgentScheduler` validates every `schedules:` entry (even when scheduling is disabled) and registers triggers.
5. The embedded web server starts on `127.0.0.1` and serves `/api/**`, `/admin/**`, `/actuator/**`.

## Where the admin console fits

The Vue 3 console is a static bundle built from `oryxos-web/src/main/frontend/` into `oryxos-web/src/main/resources/static/admin/` and served by the same Spring Boot JAR. It only calls the public REST API; it has no privileged path into the server. See [web-service-design.md](../coursework/week04/web-service-design.md).
