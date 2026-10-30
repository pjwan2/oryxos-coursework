# OryxOS Coursework — AI Agent Runtime

> Java 21 and Spring Boot AI Agent Runtime — coursework implementation with memory, sandboxing, scheduling and observability.

![Java](https://img.shields.io/badge/Java-21-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green) ![Status](https://img.shields.io/badge/status-coursework%20(not%20production)-orange)

- [1. Project overview](#1-project-overview) · [2. Scope and attribution](#2-project-scope--coursework-attribution) · [3. Architecture](#3-architecture) · [4. Technology stack](#4-technology-stack) · [5. Modules](#5-maven-module-structure)
- [6. Prerequisites](#6-prerequisites) · [7. Quick start](#7-quick-start) · [8. CLI](#8-cli-usage) · [9. REST API](#9-rest-api) · [10. Memory / Sandbox / Scheduler](#10-memory--sandbox--scheduler) · [11. Admin dashboard](#11-admin-dashboard)
- [12. Testing and quality gates](#12-testing--quality-gates) · [13. Security boundaries](#13-security-boundaries) · [14. Known limitations](#14-known-limitations) · [15. Week 04 submission](#15-coursework-week-04-submission-课程第-4-周交付) · [16. Week 05 submission](#16-coursework-week-05-submission-课程第-5-周交付)

## 1. Project overview

An agent runtime written in Java: a ReAct loop that talks to a language model, calls tools under a per-agent sandbox, keeps long-term memory, runs agents on a schedule, and is driven from a command line, an HTTP API or a Vue admin dashboard. Nine Maven modules, one executable Spring Boot JAR, SQLite for state.

**Status: coursework, not production software.** There is **no authentication**; the server binds `127.0.0.1` and is for trusted local use only. Agents are declared as `AGENT.md` directories and managed at runtime (week 5); three business agents (weather, tech digest, GitHub digest) ship in [`examples/agents/`](examples/agents/).

**What was verified, and how.** Almost everything is verified with a local **Mock model** (a scripted server speaking the OpenAI protocol, driving the real Spring AI client, tools, sandbox, scheduler, memory and SQLite audit). Three public **read-only** services were really called: Open-Meteo, Hacker News Search (Algolia) and the GitHub Search API. A **real LLM**, a real webhook, an MCP server, a Mem0 server, a real 48-hour unattended run, Linux/macOS and a human browser review have **never been run** against this code. See [14. Known limitations](#14-known-limitations) and the [phase-1 summary](docs/coursework/week-05/phase1-summary.md).

## 2. Project scope & coursework attribution

- This is an **independent coursework implementation**, written while following an online AI-programming course (极客时间 AI 编程实战营). It is **not** the official `oryx-labs/oryxos` repository, is not affiliated with or endorsed by it, and may differ from it in structure, names and behaviour.
- **No third-party source, templates or course material is knowingly copied into this repository.** Lecture slides, PDFs and paid course content are not included and must not be added; where the lecture text was not available, the documents say "awaiting confirmation" instead of guessing. If you find something that should not be here, please open an issue.
- **Licence: none chosen.** No `LICENSE` file is provided, so by default all rights are reserved by the author; this repository is published for coursework review. Dependencies are fetched by Maven and npm under their own licences (Spring, Spring AI, springdoc, Vue and Vite are Apache-2.0 / MIT; the bundled Inter and JetBrains Mono fonts come from `@fontsource` under the SIL OFL 1.1 and appear only in the generated build output, not in the source tree).
- Provisional stage names used during development (Gate 1, Gate 2A to 2C) appear only in the historical record under `docs/`.

## 3. Architecture

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

More: [overview](docs/architecture/overview.md) · [module boundaries](docs/architecture/module-boundaries.md) (enforced by `ArchitectureRulesTest`) · [design decisions](docs/decisions/design-decisions.md).

## 4. Technology stack

Java 21 · Spring Boot 3.5 · Spring AI 1.1 · SQLite (JDBC + JPA) · Picocli · springdoc OpenAPI · Micrometer/Prometheus · Vue 3 + Vite 6 · JUnit 5, Mockito, vitest · Spotless, Checkstyle, PMD, SpotBugs · GitHub Actions.

## 5. Maven module structure

| Module | Responsibility |
|---|---|
| `oryxos-core` | Domain and ports: orchestration, session, tool / memory / sandbox / schedule contracts. JDK only. |
| `oryxos-provider` | Provider name → Spring AI `ChatModel` call; YAML profile loading; failure classification. |
| `oryxos-tool` | Built-in tools, sandbox implementation, the only code that executes tools, audit wrapper, MCP client, notify. |
| `oryxos-memory` | Memory backends (Markdown, SQLite, Mem0) and the save/recall tools. |
| `oryxos-storage` | All relational persistence: sessions, audits, scheduler store, schema and migrations. |
| `oryxos-scheduler` | Cron, time zones, overlap protection; runs agents only through `AgentService`. |
| `oryxos-cli` | The command line. |
| `oryxos-web` | REST controllers, error envelope, local-access rule, the Vue dashboard (`src/main/frontend`). |
| `oryxos-boot` | Composition root and whole-system tests; builds the executable JAR. |

## 6. Prerequisites

JDK 21 · Maven 3.9+ · Node ≥ 20.19 with npm (the web module refuses to build without the dashboard) · Python 3.9+ (optional: helper scripts, and **required** to run the GitHub digest example's script; the example calls `python`, on Linux/macOS change it to `python3`). No API key, database server or network service is needed to build, test and start.

## 7. Quick start

```bash
git clone --branch submission/week-05 https://github.com/pjwan2/oryxos-coursework.git oryxos-coursework && cd oryxos-coursework
cd oryxos-web/src/main/frontend && npm ci && npm test && npm run build && cd ../../../..
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify                       # all tests + static analysis + real-JAR smoke test
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar
```

`scripts/build-all.sh` does the same. Open <http://127.0.0.1:8080/admin/>, <http://127.0.0.1:8080/swagger-ui.html>, <http://127.0.0.1:8080/actuator/prometheus>. Demo agents are in `.oryxos/profiles/`; more examples in [`examples/`](examples/). Configuration, data locations, troubleshooting: [docs/operations/runbook.md](docs/operations/runbook.md). Configuration template without secrets: [`config/env.example`](config/env.example).

**The three business agents** (needs Python; the offline `mock` model only echoes, so it shows the wiring but not the tool calls; a real model needs `DEEPSEEK_API_KEY` in the environment, never in a file):

```bash
python scripts/c5_install_agents.py --target .oryxos/agents --provider mock --model mock-echo   # drop the two flags for deepseek
python scripts/c5_local_receiver.py &            # a local "chat group" that prints what agents send (127.0.0.1:18080)
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar \
  --oryxos.sandbox.hosts=api.open-meteo.com,hn.algolia.com,127.0.0.1 --oryxos.sandbox.allow-private-networks=true \
  --oryxos.sandbox.read-roots=.oryxos/workspace,.oryxos/agents --oryxos.sandbox.commands=python
curl -X POST http://127.0.0.1:8080/api/v1/schedules/weather-daily.morning/run     # same engine as the 08:00 cron
```

`allow-private-networks` and `127.0.0.1` exist only so notifications can reach the local receiver; they are a **local demo** setting, not a production default. Full steps, triggers and the script's security boundary: [demo runbook](docs/coursework/week-05/demo-runbook.md).

## 8. CLI usage

```bash
JAR=oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar     # built by `mvn clean verify`
java -jar $JAR status
java -jar $JAR profile list
java -jar $JAR tool list
java -jar $JAR chat memory-demo "hello" --user demo      # one message; omit the message for an interactive chat
java -jar $JAR serve --server.port=8081                  # REST server (also the default with no arguments)
```

With the Mock model, a message `tool:<name> <json>` makes the agent call that tool if its Profile lists it, e.g. `chat memory-demo 'tool:save_memory {"content":"met Sam"}'`.

## 9. REST API

Every answer uses `{code, message, data, timestamp}` and carries `X-Trace-Id`. Interactive docs: `/swagger-ui.html`.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/sessions`, `GET /api/v1/sessions`, `GET`/`DELETE /api/v1/sessions/{id}`, `POST /api/v1/sessions/{id}/messages` | create / list (latest 100) / read (latest 100 messages) / delete / send (≤ 32 KiB) |
| `POST /api/v1/agents/{name}/invoke` | one call to an agent |
| `GET /api/v1/profiles`, `/tools`, `/memory`, `/tool-calls` | read-only queries |
| `GET /api/v1/health`, `/info` | probe (503 if the database is down) and status |
| `GET /api/v1/schedules`, `/{id}/executions`, `POST /{id}/run`, `PUT /{id}` | scheduler operations |
| `GET /api/v1/schedules/{id}/executions/{executionId}/outcome` | what a finished run achieved: technical status plus tool audit (`COMPLETED`, `COMPLETED_WITH_TOOL_ISSUES`, `FAILED`) |
| `POST/GET /api/v1/agents`, `GET/PUT/DELETE /api/v1/agents/{name}` | create (fixed template), list, read, replace `AGENT.md` (validated, rolled back on failure), archive (never deletes) |
| `GET /api/v1/agents/{name}/session`, `POST .../session/messages` | the agent's fixed console conversation (`admin:console:<name>`), same `AgentService` |
| `GET /api/v1/agents/{name}/memory` | the agent's private long-term memory (or the shared global one for a legacy profile) |
| `POST /api/v1/agents/{name}/generate-files`, `POST .../files` | model drafts files (nothing saved) / confirmed files are saved and take effect |
| `GET /api/v1/workspace/tree`, `GET/POST /api/v1/workspace/file` | restricted text files of the registered agents |

Codes: 400 bad input · 403 not local · 404 unknown · 409 scheduler conflict · 500 fixed text only · 503 model/memory unavailable · 504 model timeout. Design: [web service design](docs/coursework/week-04/web-service-design.md).

## 10. Memory / Sandbox / Scheduler

- **Memory** — `save_memory` / `recall_memory` over one interface with Markdown (default), SQLite and Mem0 backends; CORE entries are always in the prompt, ARCHIVAL entries are searched. → [design](docs/coursework/week-04/memory-design.md)
- **Sandbox** — an agent's rights are its Profile's declaration **intersected** with the operator ceiling; no declaration means deny everything; every refusal is audited. An application-level policy, **not** an OS sandbox. → [design](docs/coursework/week-04/sandbox-design.md)
- **Scheduler** — cron + time zone per task in the Profile, no overlap, state and history in SQLite, survives restart; single instance only. → [design](docs/coursework/week-04/scheduler-design.md)

## 11. Admin dashboard

Vue 3 + Vite in `oryxos-web/src/main/frontend/`, built into `oryxos-web/src/main/resources/static/admin/` (git-ignored) and served by the same JAR under `/admin/`. Pages: Sessions, Profiles, Tools, Memory, System (read-only), Scheduler (list, history, run now, enable/disable; technical run status and tool audit are shown separately, so a sandbox refusal is never shown as success) and Agents (create, edit `AGENT.md`, files, console session, private memory, "Scheduled runs" with per-run tool calls). `npm run dev` (port 5173) proxies `/api` to a server on 8080. Conventions: [`.claude/skills/oryxos-admin-ui/SKILL.md`](.claude/skills/oryxos-admin-ui/SKILL.md). The dashboard was checked with unit tests and an automated headless browser; **no human review** was done.

## 12. Testing & quality gates

```bash
mvn -B -ntp clean verify         # Spotless, Checkstyle, PMD, SpotBugs, all tests, WebSmokeIT on the real JAR
mvn -B -ntp -pl oryxos-memory -am test            # one module and what it depends on
python scripts/week04_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar   # 77 checks on the real JAR (week 4)
python scripts/week05_c5_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar --out /tmp/c5   # 64 checks: the three demos
```

The gate never skips tests and never excludes a static check. The exact results are in the [week 5 acceptance report](docs/coursework/week-05/acceptance-report.md) (week 4: [acceptance report](docs/coursework/week-04/acceptance-report.md)) and the `evidence/` folders next to them. Skipped tests (symlink tests on Windows, which cannot create symlinks without privilege) are recorded as skipped, never as passed. Contribution rules: [CONTRIBUTING.md](CONTRIBUTING.md).

## 13. Security boundaries

- No authentication or users. The server binds `127.0.0.1`; session, memory, tool-call, invoke and scheduler-write endpoints additionally accept only loopback callers without forwarding headers and with a local `Origin` and `Host`. This is a stop-gap, **not** security: any local process can call it. Do not expose the port.
- The sandbox is an in-JVM policy (paths, hosts, commands). It does not limit CPU, memory or network, does not stop an allowed program from doing harm, and does not remove DNS-rebinding or check-then-use races. For untrusted prompts run it inside a container or a low-privilege account.
- Secrets come from the environment only; none are in this repository (checked with `scripts/security_audit.py`, which also scans the Git history). Rules: [data and secrets](docs/operations/data-and-secrets.md).

## 14. Known limitations

Never verified: a real LLM, a real webhook, an MCP server, a Mem0 server, a real 48-hour unattended run, Linux/macOS runs (CI on Ubuntu aside), a human browser review. Really verified read-only: Open-Meteo, Hacker News Search, GitHub Search. Not implemented: authentication, OS-level isolation (a trusted Python script can use the network outside the `http_get` whitelist), multi-instance scheduling. Legacy `profiles/*.yaml` agents still share one global memory; `AGENT.md` agents have private memory. The GitHub list is an approximation, **not** the official Trending. Full lists: [week 5](docs/coursework/week-05/known-limitations.md), [week 4](docs/coursework/week-04/known-limitations.md).

## 15. Coursework Week 04 Submission (课程第 4 周交付)

**范围。** 极客时间 AI 编程实战营第 4 周：第 21、22 课 Memory（设计评审与实现）、第 23、24 课 Sandbox（安全评审与实现）、第 25 课 定时任务、第 26 课 REST 服务与管理台（含第 27 课的会话列表、第 28 课的 Scheduler 界面）。

**提交分支：`submission/week-04`。** 从这里开始读：

| 文件 | 内容 |
|---|---|
| [docs/coursework/week-04/README.md](docs/coursework/week-04/README.md) | 本周作业说明 |
| [requirements-matrix.md](docs/coursework/week-04/requirements-matrix.md) | 要求 → 源码 → 测试 → 证据 → 状态（含"等待核对"项） |
| [acceptance-report.md](docs/coursework/week-04/acceptance-report.md) | 验收报告：命令、测试数量、提交 SHA |
| [architecture.md](docs/coursework/week-04/architecture.md)、[memory](docs/coursework/week-04/memory-design.md)、[sandbox](docs/coursework/week-04/sandbox-design.md)、[scheduler](docs/coursework/week-04/scheduler-design.md)、[web service](docs/coursework/week-04/web-service-design.md) | 设计说明 |
| [known-limitations.md](docs/coursework/week-04/known-limitations.md) | 未验证与未实现的事项 |
| `deliverables/week-04/` | 提交包内容（`python scripts/package_week04.py` 生成 `oryxos-week-04-coursework.zip` 与 `oryxos-week-04-source.zip`，ZIP 不入库） |

老师的正式评分标准和本周「本周作业」页面的具体题目目前**未核实**，矩阵中相关判断标为"等待核对"。过程记录（阶段 A 至 B4）见 [docs/course-progress.md](docs/course-progress.md)，文档索引见 [docs/README.md](docs/README.md)。

## 16. Coursework Week 05 Submission (课程第 5 周交付)

**范围。** 极客时间 AI 编程实战营第 5 周：第 27 课 主流程集成、第 28 课 稳定性与多 Agent、第 29 课 `AGENT.md` 插件化、第 30 课 动态 Agent 管理、第 31 课 三个业务 Demo、第 32 课 总结与验收。

**提交分支：`submission/week-05`；标签 `week-05`。** 从这里开始读：

| 文件 | 内容 |
|---|---|
| [docs/coursework/week-05/README.md](docs/coursework/week-05/README.md) | 本周作业说明与第 27～32 节完成矩阵 |
| [phase1-summary.md](docs/coursework/week-05/phase1-summary.md) | 第一阶段总结：架构、Runtime 与业务 Agent 的分界、`AGENT.md`、取舍与安全边界 |
| [requirements-matrix.md](docs/coursework/week-05/requirements-matrix.md) | 要求 → 源码 → 测试 → 证据 → 状态（Mock / 真实只读 / 未验证分开写） |
| [acceptance-report.md](docs/coursework/week-05/acceptance-report.md) | 验收报告：命令、测试数量、提交 SHA、干净克隆结果 |
| [demo-runbook.md](docs/coursework/week-05/demo-runbook.md) | 三个 Demo 的安装、触发、数据源、脚本安全边界 |
| [known-limitations.md](docs/coursework/week-05/known-limitations.md) | 未验证与未实现的事项 |
| [`examples/agents/`](examples/agents/) | 三个 Agent 源文件 |
| `docs/coursework/week-05/evidence/` | 构建日志、真实 JAR 检查记录、真实只读外部 API 记录 |

GitHub 上的发布**不等于**课程平台提交；平台提交状态见验收报告（目前 **Pending**）。迭代总览：[docs/coursework/iteration-log.md](docs/coursework/iteration-log.md)。
