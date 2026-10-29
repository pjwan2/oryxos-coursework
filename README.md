# OryxOS — Agent Runtime Engineering

An agent runtime in Java: a ReAct loop that talks to a model, calls tools under a per-agent sandbox, keeps long-term memory, runs on a schedule, and is driven from a command line, an HTTP API or a Vue admin console. Nine Maven modules, one executable Spring Boot JAR, SQLite for state.

> **Status: course implementation, not production software.** It has **no authentication** and is for trusted local use only (the server binds `127.0.0.1`). Everything has been verified with a local **Mock model**; a real LLM, MCP server, webhook receiver and Mem0 server have **never been run** against it. See [Known limitations](#known-limitations).

## Relationship to the official course repository

This is an **independent coursework implementation** written while following the course. It is not the official `oryx-labs/oryxos` repository, is not affiliated with it, and may differ from it in structure, names and behaviour. Where the lecture material was not available (it is not in this repository), the docs say "awaiting confirmation" instead of guessing.

## Architecture

CLI, REST and the scheduler all enter through the same `AgentService`:

```
 CLI (Picocli)      REST controllers       AgentScheduler (cron)
       \                  |                      /
        +---------> AgentService.process(channel, userId, profile, message) <---+
                           |  session id = channel:userId:profile
                       ReActLoop
                  /                 \
   ModelGateway -> ProviderService     AuditedToolExecutor -> RegistryToolExecutor
   (Spring AI, tool auto-exec OFF)        -> Profile allow-list -> Sandbox -> OryxTool
                           |
   SQLite: sessions, llm_calls, tool_invocations, memory_entries, scheduled_tasks, ...
```

Details: [docs/architecture/overview.md](docs/architecture/overview.md), [module boundaries](docs/architecture/module-boundaries.md) (enforced by `ArchitectureRulesTest`), [design decisions](docs/decisions/design-decisions.md).

## Tech stack

Java 21 · Spring Boot 3.5 · Spring AI 1.1 · SQLite (JDBC + JPA) · Picocli · springdoc (OpenAPI) · Micrometer/Prometheus · Vue 3 + Vite 6 · JUnit 5 / Mockito · vitest · Spotless, Checkstyle, PMD, SpotBugs.

## Modules

| Module | Responsibility |
|---|---|
| `oryxos-core` | Domain and ports: agent orchestration, session, tool/memory/sandbox/schedule contracts. JDK only. |
| `oryxos-provider` | Provider name → Spring AI `ChatModel` call; YAML profile loading; failure classification. |
| `oryxos-tool` | Built-in tools, sandbox implementation, the only code that executes tools, audit wrapper, MCP client, notify. |
| `oryxos-memory` | Long-term memory backends (Markdown, SQLite, Mem0) and the save/recall tools. |
| `oryxos-storage` | All relational persistence: sessions, audits, scheduler store, schema and migrations. |
| `oryxos-scheduler` | Cron triggering, time zones, overlap protection; runs agents only through `AgentService`. |
| `oryxos-cli` | The command line. |
| `oryxos-web` | REST controllers, error envelope, local-access rule, the Vue admin console (`src/main/frontend`). |
| `oryxos-boot` | Composition root and the whole-system tests; builds the executable JAR. |

## Requirements

JDK 21 · Maven 3.9+ · Node ≥ 20.19 with npm (front end; the web module refuses to build without it) · Python 3.9+ (optional: helper scripts). No API key, database server or network service is needed.

## Quick start

```bash
cd oryxos-web/src/main/frontend && npm ci && npm test && npm run build && cd ../../../..
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify                       # all tests + static analysis + real-JAR smoke test
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar
```

`scripts/build-all.sh` does the same. Then open <http://127.0.0.1:8080/admin/>, <http://127.0.0.1:8080/swagger-ui.html>, <http://127.0.0.1:8080/actuator/prometheus>.

Demo agents are in `.oryxos/profiles/` (`payment-demo`, `memory-demo`); more examples in [`examples/`](examples/). Configuration, data locations and operations: [docs/operations/runbook.md](docs/operations/runbook.md).

## CLI

```bash
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar status
java -jar ... profile list
java -jar ... tool list
java -jar ... chat memory-demo "hello" --user demo      # one message; omit the message for an interactive chat
java -jar ... serve --server.port=8081                  # start the REST server (also the default with no arguments)
```

With the Mock model, a message `tool:<name> <json>` makes the agent call that tool (if its Profile lists it), e.g. `chat memory-demo 'tool:save_memory {"content":"met Sam"}'`.

## REST API

All answers use `{code, message, data, timestamp}`; every response has `X-Trace-Id`. Interactive docs at `/swagger-ui.html`.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/sessions`, `GET /api/v1/sessions`, `GET`/`DELETE /api/v1/sessions/{id}`, `POST /api/v1/sessions/{id}/messages` | session create / list (latest 100) / read (latest 100 messages) / delete / send (≤ 32 KiB) |
| `POST /api/v1/agents/{name}/invoke` | one call to an agent |
| `GET /api/v1/profiles`, `/tools`, `/memory`, `/tool-calls` | read-only queries |
| `GET /api/v1/health`, `/info` | probe (503 if the database is down) and status |
| `GET /api/v1/schedules`, `/{id}/executions`, `POST /{id}/run`, `PUT /{id}` | scheduler operations |

Status codes: 400 bad input · 403 not local · 404 unknown · 409 scheduler conflict · 500 fixed text only · 503 model or memory unavailable · 504 model timeout. Session, memory, tool-call, invoke and scheduler-write endpoints accept only local callers; this is a stop-gap, **not authentication**.

## Memory, Sandbox, Scheduler

- **Memory**: `save_memory` / `recall_memory` tools over one interface with Markdown (default), SQLite and Mem0 backends; CORE entries always in the prompt, ARCHIVAL searched. → [memory design](docs/coursework/week-04/memory-design.md)
- **Sandbox**: an agent's rights are what its Profile declares **intersected** with the operator ceiling; no declaration means deny everything; every refusal is audited. It is an application-level policy, **not** an OS sandbox. → [sandbox design](docs/coursework/week-04/sandbox-design.md)
- **Scheduler**: cron + time zone per task in the Profile, no overlap, state and history in SQLite, survives restart; single instance only. → [scheduler design](docs/coursework/week-04/scheduler-design.md)

## Tests

```bash
mvn -B -ntp clean verify                                   # everything (Spotless, Checkstyle, PMD, SpotBugs, tests, WebSmokeIT)
mvn -B -ntp -pl oryxos-memory -am test                     # one module and what it depends on
mvn -B -ntp -pl oryxos-boot -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false
python scripts/week04_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar   # 77 checks on the real JAR
```

## Front end

Vue 3 + Vite in `oryxos-web/src/main/frontend/`, built into `oryxos-web/src/main/resources/static/admin/` (git-ignored) and served by the same JAR under `/admin/`. `npm run dev` (port 5173) proxies `/api` to a running server on 8080. Conventions: [`.claude/skills/oryxos-admin-ui/SKILL.md`](.claude/skills/oryxos-admin-ui/SKILL.md).

## Known limitations

Not verified: a real LLM, MCP server, webhook receiver, Mem0 server; four symlink tests are skipped on Windows (no privilege). Not implemented: authentication, OS-level isolation, multi-instance scheduling. No human browser review of the console was done. Full list: [week-4 known limitations](docs/coursework/week-04/known-limitations.md). Do not expose this server to a network.

## Course progress and evidence

[docs/README.md](docs/README.md) is the documentation index. Chronological record of every stage with its evidence: [docs/course-progress.md](docs/course-progress.md). Week 4 assignment: [docs/coursework/week-04/](docs/coursework/week-04/README.md) (requirements matrix, acceptance report, evidence). Submission material is generated into `deliverables/` by `scripts/package_week04.py`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) (branch names, build gate, repository layout rules). No license has been chosen for this coursework.
