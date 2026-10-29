# Week 3 (lessons 15 to 20): foundation, Provider, ReAct, CLI, Notify, Tool

Stage A of the project. The detailed record, with the commands run and the evidence at the time, is the stage A section of [../../course-progress.md](../../course-progress.md) (commit `9c07dc0`). This folder only points at it; nothing was re-verified or rewritten for the reorganisation.

| Lesson | Topic | Where it lives now |
|---|---|---|
| 15 | init Skill, nine-module skeleton, logging, API, static checks, CI | root `pom.xml`, `config/checkstyle/`, `.github/workflows/ci.yml`, [course-mapping.md](course-mapping.md) (the lesson 15 mapping) |
| 16 | Profile, Provider routing, Spring AI call with tool auto-execution off, `llm_calls` audit | `oryxos-provider`, [run book of the time](../../history/lesson-16-runbook.md) |
| 17 | `ReActLoop`, `PromptBuilder`, `AgentService`, session and tool audit | `oryxos-core/.../agent`, `oryxos-boot/.../Lesson17RuntimeConfiguration.java`, [run books of the time](../../history/lesson-17-runbook.md) |
| 18 | Picocli CLI, session persistence in SQLite | `oryxos-cli`, `oryxos-storage` |
| 19 | Notify adapters and tool | `oryxos-tool/.../notify` |
| 20 | `OryxTool`, registry, built-in tools, MCP client | `oryxos-core/.../tool`, `oryxos-tool` |

What has not been verified (real LLM, real MCP server, real webhook) is listed in the stage A section of the progress log and in [../week-04/known-limitations.md](../week-04/known-limitations.md).

The original lecture text for lessons 16 to 20 is not in this repository; the older run books are kept as history because their commands refer to earlier directory layouts. Use the [current run book](../../operations/runbook.md).
