# Week 5 (lessons 27 to 32): Agent lifecycle, plugins and three business demos

Written for: course reviewers and the project owner. Branch `submission/week-05`, tag `week-05` (created only after CI is green; see the acceptance report for the exact commit). Developed on `feature/week-05-agent-lifecycle`, which was created from the `week-04` tag.

**Honest status.** Everything below is **Mock-verified** unless a row says otherwise. Three public read-only services (Open-Meteo, Hacker News Search, GitHub Search) were really called. A real LLM, a real webhook, a real 48-hour run, a news MCP server, Linux/macOS and a human browser review were **not** done. GitHub publication is not the same as the course-platform submission, which is **Pending**.

## Completion matrix, lessons 27 to 32

| Lesson | Topic | Stage | Status | Evidence |
|---|---|---|---|---|
| 27 | Main-flow integration (weather, memory, audit reconciliation) | C1 | Mock verified (commit `178411b`); real LLM integration test `HumanTriggerFlowIT` **not implemented** (no key) | [requirements-matrix](requirements-matrix.md) 27.x |
| 28 | Stability: scheduler, no-overlap, restart recovery, multi-agent isolation | C2 | Mock verified (commit `49777b7`); real JAR 31/31 | matrix 28.x, `evidence/live-check.txt` |
| 29 | Declarative `AGENT.md` plugins, dynamic registry, progressive disclosure | C3 | Mock verified (`8639b92`) | matrix 29.x, `evidence/live-check-c3-c4.txt` |
| 30 | Dynamic lifecycle, CRUD API, watcher, per-agent memory, execution ids, admin console | C4 | Mock verified (`6dd7598`); real JAR 96/96 (C3 + C4); no authentication | matrix 30.x |
| 31 | Three business agents: weather, tech digest, GitHub digest | C5 | Mock verified (`73723e3`, `2afd1f8`, `58988ae`, `26db43f`, `32372ac`); Open-Meteo / HN Search / GitHub Search **really verified (read-only)**; real LLM and real webhook **not verified** | matrix 31.x, [demo-runbook](demo-runbook.md), `evidence/c5/` |
| 32 | Review, final acceptance, GitHub release | C6 | Done on a clean clone, see [acceptance-report](acceptance-report.md) section "C6"; platform submission **Pending** | [phase1-summary](phase1-summary.md), matrix 32.x |

## Read first

| File | Content |
|---|---|
| [phase1-summary.md](phase1-summary.md) | Phase-1 summary: architecture, runtime vs business agents, `AGENT.md`, shared engine, demos, trade-offs, security boundaries, next steps |
| [requirements-matrix.md](requirements-matrix.md) | Requirement → Source → Test → Result → Evidence for lessons 27 to 32 |
| [acceptance-report.md](acceptance-report.md) | Commands, test totals, real-JAR checks, commit SHAs, clean-clone result, release state |
| [demo-runbook.md](demo-runbook.md) | Install and run the three agents; the three trigger paths; the script's security boundary |
| [known-limitations.md](known-limitations.md) | What is not verified or not implemented |
| [evidence/](evidence/README.md) | Build logs, test summary, real-JAR transcripts, saved real read-only API responses |
| [`../../../examples/agents/`](../../../examples/agents/) | The three agent source directories |

## What this week added

- **Lessons 27 and 28**: end-to-end tests that run the real Spring AI client, ReAct loop, tools, sandbox, memory and SQLite audit against a loopback server that only scripts the model's answers; a fix for provider calls that held the request thread for minutes; a read-only `outcome` endpoint that separates technical status from the tool audit.
- **Lesson 29**: a directory with an `AGENT.md` is an agent (YAML header with the existing profile fields, Markdown body as the instructions); a mutable thread-safe registry; scheduler registration; the sandbox is re-evaluated on every tool call against the latest definition; resources are listed by name and never preloaded.
- **Lesson 30**: one lifecycle service shared by startup scan, file watcher and REST; create/read/replace/archive with rollback; private memory per agent; `execution_id` (migration V4) for exact audit reconciliation; per-session serialization; restricted workspace API; AI-drafted files that are saved only after confirmation; the Agents section of the admin console.
- **Lesson 31**: `examples/agents/{weather-daily, daily-tech-digest, github-daily}`; a trusted stdlib-only Python script; real Cron firing observed in an isolated run; a controllable clock for "two consecutive days"; a "Scheduled runs" panel.
- **Lesson 32**: the phase-1 summary, the clean-clone acceptance and the release.

## Numbers

The final numbers (tests, front end, real-JAR checks) are in [acceptance-report.md](acceptance-report.md), taken from a clean clone of the release commit. Week 4 had 502 tests.

## Course roadmap notes

The topics come from roadmap notes kept outside this repository (`OryxOS_When_Homework_Implementation_v1/docs/EXECUTION_ROADMAP.md`, a summary of lecture notes, **not** the official grading standard). Lecture slides and PDFs are not in this repository. The notes disagree on whether lesson 31 asks for two demos or three; three are delivered. The teacher's grading rules and the submission page's exact requirements are **awaiting confirmation**.
