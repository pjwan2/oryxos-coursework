# Week 5 (lessons 27 to 32): in progress — C1, C2 and C3 done, C4 to C6 not started

Branch `feature/week-05-agent-lifecycle` (from the `week-04` tag). **Not pushed and not tagged**: week 5 is not finished.

| Stage | Lessons | Status |
|---|---|---|
| **C1** main-flow integration | 27 | done, verified with a local model and fixtures (commit `178411b`) |
| **C2** stability and multi-agent | 28 | done, verified with a local model and fixtures (commit `49777b7`); per-agent memory isolation **not implemented** |
| C3 plugin agents (`AGENT.md`) | 29 | done, verified with the Mock provider (commit: see `git log`, `feat(week05): support declarative AGENT.md plugins`) |
| C4 dynamic agent management | 30 | not started |
| C5 demos | 31 | not started |
| C6 review | 32 | not started |

## Read first

| File | Content |
|---|---|
| [requirements-matrix.md](requirements-matrix.md) | Requirement → Source → Test → Result → Evidence for lessons 27 and 28 |
| [acceptance-report.md](acceptance-report.md) | Commands, test totals (538 / 4 skipped / 0 failed), real-JAR checks, commit SHAs |
| [known-limitations.md](known-limitations.md) | What is not verified or not implemented, and what must be solved before lessons 29 and 30 |
| [evidence/](evidence/README.md) | Build log, test summary, live-check transcripts |

## What this stage added

- End-to-end tests (no API key, no public network) that run the real Spring AI client, ReAct loop, tools, sandbox, memory and SQLite audit against a loopback server that only scripts the model's answers: `MockAgentE2ETest`, `MockProviderFlowTest`, `SchedulerStabilityE2ETest`, `MultiAgentIsolationE2ETest`, `FullStackRestartRecoveryTest`.
- A fix found by the failure-path test: a provider that was down held the request thread for minutes because of Spring AI's default retry; provider calls are now bounded (`ProviderConfiguration`).
- A read-only `GET /api/v1/schedules/{id}/executions/{executionId}/outcome` that separates technical status from the tool audit (`COMPLETED`, `COMPLETED_WITH_TOOL_ISSUES`, `FAILED`); the stored status and existing API are unchanged.
- `scripts/week05_live_check.py`: 31 checks on the real JAR, including a JVM restart with a table-by-table comparison.

## Course roadmap notes for the rest of the week

The topics below come from the roadmap notes kept outside this repository (`OryxOS_When_Homework_Implementation_v1/docs/EXECUTION_ROADMAP.md`, a summary of lecture notes, **not** the official grading standard):

- Lesson 29: plugin agents (`.oryxos/agents/<name>/AGENT.md` as the single source of truth; migrate from `profiles/*.yaml`, never keep both writable).
- Lesson 30: dynamic agent management and the dashboard CRUD (no unauthenticated exposure; preview before create).
- Lesson 31: demos (the notes disagree whether there are two or three; the official submission instructions decide).
- Lesson 32: review.

Submission rules for the week: awaiting confirmation.
