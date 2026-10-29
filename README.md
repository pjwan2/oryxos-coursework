# OryxOS Gate 2C — ReAct Session & Tool Audit (v1.5)

This is a **coursework intermediate artifact** built on v1.4: JDK 21, Spring Boot 3.5.16, Spring AI 1.1.4 and SQLite. It is NOT the finished OryxOS Agent OS.

See [`docs/LESSON17_PHASE2_RUNBOOK.md`](docs/LESSON17_PHASE2_RUNBOOK.md) for Windows Git Bash commands.

What changed: AgentService, ProfileContext cleanup, SQLite session + tool audit, read-only payment mock HTTP API, and tests for failure paths.

**Not yet implemented:** real Spring AI ReAct tool-call bridge, ContextLoader/Memory, full Sandbox, Picocli session chat, Notify/Scheduler, dynamic Agent definitions. Do not submit this slice as if it satisfies the whole lesson 17 or weeks 3–7.

First verification on your computer:

```bash
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

On Windows, keep the current v1.4 directory unchanged as the last known working checkpoint. This artifact has only been partially checked offline; Maven + Spring integration tests must run on your machine.
