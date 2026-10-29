# Scheduler design (lesson 25, with the lesson 28 management surface)

Covers lesson 25 (principles, implementation, code walk-through). The REST operations and the admin page that belong to lesson 28 are included because B3/B4 built them on top of this module.

> The lecture text for lesson 25 is not in the repository. The alternatives table is general knowledge, marked *(unverified against the lecture)*.

## 1. Problem

Let an agent run by itself ("every weekday 09:00 summarise the briefing"): reliably, in the right time zone, never twice at once, with a record of what happened, surviving a restart, and **with exactly the same rights and code path as a person chatting with it**.

## 2. Alternatives *(unverified against the lecture)*

| Option | Notes | Verdict here |
|---|---|---|
| OS cron / Windows Task Scheduler starting the CLI | Simple; one JVM start per run; no in-process state | Not chosen: no shared state or overlap control with the server |
| Spring `@Scheduled` | Annotation on fixed methods; tasks are code, not Profile data | Not chosen: tasks come from Profile YAML at runtime |
| **Spring `TaskScheduler` + `CronTrigger(cron, zone)`** | Programmatic registration, time zone aware, no extra library | **Chosen** |
| Quartz | Full-featured, persistent jobs, clustering | Heavier than the lesson scope |
| ShedLock / database locks | Cross-instance mutual exclusion | Not built; single instance is the stated scope |
| Workflow systems (Airflow, XXL-Job, Kubernetes CronJob) | Distributed, rich retries | Out of scope |

## 3. Definition: schedules live in the Profile

```yaml
schedules:
  - id: morning-brief
    cron: "0 0 9 * * MON-FRI"      # Spring's SIX fields, seconds first
    zone: Asia/Shanghai            # mandatory
    message: "Summarise today's briefing notes."
```

`ScheduleConfigParser` validates strictly and **startup fails on any error**, with the profile name and entry position: all four keys required and textual; six-field cron (the five-field Unix form is rejected); `zone` must be given and valid; unknown keys, duplicate ids in one Profile, unsafe id or profile names, a cron that can never fire, a firing interval under **30 seconds** (guards against accidental high-frequency model calls when unattended) and messages over 2,000 characters are refused. Validation also runs when scheduling is disabled (`oryxos.scheduler.enabled=false`); that flag only decides whether triggers are registered.

Task id = `<profile>.<id>` (the dot cannot appear in either part, so ids are unique across agents; two agents may use the same local id, see `MultiAgentSchedulerIsolationTest`).

## 4. Components

| Piece | Module | Role |
|---|---|---|
| `ScheduleService`, `ScheduledTaskStore`, `ScheduledTask`, `TaskExecution`, `ScheduleView`, `ScheduleUpdate` and the exceptions | core | contract and records |
| `ScheduleConfigParser`, `ScheduleConfig` | scheduler | parse and validate |
| `AgentScheduler` (`ScheduleService` + `SmartLifecycle`) | scheduler | register triggers, `fire` (cron), `runNow` (manual), `list`, `executions`, `update` |
| `JdbcScheduledTaskStore` | storage | `scheduled_tasks`, `task_executions` |
| `SqliteMigrator`, `Migrations`, `db/schema.sql` | storage | versioned schema (V1 baseline, V2 `tool_invocations.error_message`, V3 the two scheduler tables) |
| `SchedulerConfiguration` | boot | pool size, the `AgentScheduler` bean, injection of the shared `AgentService` |
| `ScheduleApiController` | web | four operations over REST |

## 5. Run semantics

```
cron trigger (CronTrigger(cron, ZoneId))  --fire(taskId)-->  skip if disabled; skip if already running
manual  POST /schedules/{id}/run          --runNow--------->  runs even if disabled; 409 if already running
                                  |
        single-flight lock for this task id (ConcurrentHashMap key set, released in finally)
                                  |
        AgentService.process("scheduler", "scheduler", <profile>, <message>)     <- the same bean as CLI and REST
                                  |
        record TaskExecution(trigger=cron|manual, success, safe error text, duration) + update counters
```

- **Identity.** `channel=scheduler`, `user=scheduler`, so the session id is `scheduler:scheduler:<profile>` and every task of one Profile shares that one session (history grows without bound; the prompt only uses the last 20 messages).
- **Permissions.** None of their own. The sandbox comes from the Profile exactly as in a chat. A Profile that lists tools but no `settings.sandbox` produces a startup WARN, because unattended, the sandbox is the only defence.
- **No overlap.** At most one run per task at a time inside the process. 16 concurrent triggers produce one execution (`AgentSchedulerTest`; mutation check: removing the lock made three tests fail).
- **Failure isolation.** An exception becomes a `FAILED` record with `Agent run failed (<ExceptionClass>)` (never the exception message), the lock is released in `finally`, other tasks are unaffected; failing to write the record is only logged.
- **"Success" means the agent run did not throw.** A run in which the sandbox refused every tool call is a *successful* run with `SandboxDenied` audit rows. Reports and the admin console show both facts separately.
- **Manual run ignores `enabled`**, is recorded with `trigger=manual`, and does not change `enabled`.

## 6. Persistence and restart

| Table | Content |
|---|---|
| `scheduled_tasks` | `task_id`, `profile_name`, `cron`, `zone`, `message`, `enabled`, `next_run_at`, `last_run_at`, `last_status`, `run_count`, `definition_hash` |
| `task_executions` | `id`, `task_id`, `session_id`, `trigger_type`, `started_at`, `success`, `error_message`, `duration_ms` |

At startup each Profile entry is synced into `scheduled_tasks`. `definition_hash` remembers the last synced definition: if the YAML did not change, edits made through the API (cron, zone, message) are kept; if it changed, the file wins. `enabled`, counters and history are **always** kept. Tasks removed from a Profile are no longer listed or runnable; their history stays in the database. Triggers are registered again at startup, so a task disabled before the restart stays quiet afterwards (the live check waits and confirms).

Time stamps are ISO-8601 UTC text; `next_run_at` is computed from the cron in the task's zone.

## 7. Database migration (needed by this lesson)

The scheduler needed new tables and a new audit column on databases that already hold data, so a small versioned runner was added (no Flyway): `schema_migrations(version, description, checksum, applied_at)`; applied versions must be exactly 1..n (a gap, a database newer than the program, or a changed checksum of an old migration refuses startup); each migration commits together with its version row in a `BEGIN IMMEDIATE` transaction; a fresh file gets the full `schema.sql` stamped with all versions; an existing file is first copied with `VACUUM INTO`, upgraded **on the copy as a rehearsal** with row counts compared, and only then upgraded for real. `SqliteMigrationTest` compares the migrated schema with `schema.sql` table by table so the two cannot drift. Backups are not deleted automatically.

## 8. Management surface (lesson 28 part)

| Operation | Endpoint | Notes |
|---|---|---|
| List | `GET /api/v1/schedules` | state, `running`, `nextRunAt`, `lastStatus`, `runCount` |
| History | `GET /api/v1/schedules/{id}/executions?limit=` | newest first, default 50, max 200 |
| Run now | `POST /api/v1/schedules/{id}/run` | local-only; a failed run is HTTP 200 with `success:false` |
| Change | `PUT /api/v1/schedules/{id}` | local-only; `enabled`, `cron`, `zone`, `message`, validated (400) |

Errors: unknown task 404, bad value 400, already running or scheduler disabled 409, not local 403. The admin page `Scheduler` (Vue) lists tasks, shows history, runs now after a confirmation that says a manual run is allowed even when the task is disabled, toggles enabled, and keeps **technical execution** and **tool audit** apart (see [web-service-design.md](web-service-design.md)).

## 9. Single-instance boundary

The lock is a set in process memory and the scheduler assumes it is the only one on the database. Two instances on one database file will both fire; there is no distributed lock, no exactly-once, no leader election. Runs missed while the process was down are not replayed. Daylight-saving transitions follow Spring's `CronExpression`. SQLite is a single-file database; running two writers is not supported anyway.

## 10. Evidence

| Evidence | Where |
|---|---|
| Triggering, overlap, failure isolation, disabled, runNow, update, restart of the service object | `AgentSchedulerTest` 26 |
| Configuration validation | `ScheduleConfigValidationTest` 13 |
| Store round-trips | `ScheduledTaskStoreTest` 9 |
| Migrations and old-file upgrade | `SqliteMigrationTest` 20, `MigrationStartupTest` 6 |
| Two agents with the same local id stay separate | `MultiAgentSchedulerIsolationTest` 3 |
| REST contract | `ScheduleApiControllerTest` 17 |
| Whole system through the HTTP API, scheduled sandbox denial and secret-free reasons | `ScheduledTaskE2ETest` 10 |
| Restart recovery with two Spring contexts | `RestartRecoveryTest` 4 |
| Real JAR: manual run, disable, run while disabled, 404, 400, kill, restart, same state and history | `evidence/live-check.txt` section "Scheduler"; `WebSmokeIT`; B3 record (including a real cron trigger observed firing on time) in `docs/course-progress.md` |

## 11. Limitations

Single instance only; no missed-run catch-up; one shared session per Profile; "success" is "did not throw"; no per-task retry policy; no pause/resume of a running task; tool audit for a run is matched by session and time window (no execution id column yet); real model providers were not used in any scheduled run.
