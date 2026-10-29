-- Full, current schema. It is applied as-is to a brand-new database; an existing database is
-- instead upgraded step by step by SqliteMigrator (see Migrations.java) and must end up identical.
-- Keep both in sync: SqliteMigrationTest compares them table by table.
CREATE TABLE IF NOT EXISTS llm_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    success INTEGER NOT NULL,
    error_message TEXT,
    occurred_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_llm_calls_session ON llm_calls(session_id);

CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    profile_name TEXT NOT NULL,
    channel TEXT NOT NULL,
    user_id TEXT NOT NULL,
    messages_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS tool_invocations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    success INTEGER NOT NULL,
    error_category TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    occurred_at TIMESTAMP NOT NULL,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_tool_session ON tool_invocations(session_id);

-- Long-term memory (lesson 21/22). scope is CORE or ARCHIVAL; saved_on is an ISO date.
CREATE TABLE IF NOT EXISTS memory_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope TEXT NOT NULL CHECK (scope IN ('CORE', 'ARCHIVAL')),
    content TEXT NOT NULL,
    saved_on TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries(scope, id);

-- Scheduler (lesson 25/28). task_id is "<profile>.<localId>"; times are ISO-8601 UTC text.
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    task_id TEXT PRIMARY KEY,
    profile_name TEXT NOT NULL,
    cron TEXT NOT NULL,
    zone TEXT NOT NULL,
    message TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    next_run_at TEXT,
    last_run_at TEXT,
    last_status TEXT,
    run_count INTEGER NOT NULL DEFAULT 0,
    definition_hash TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_profile ON scheduled_tasks(profile_name);

CREATE TABLE IF NOT EXISTS task_executions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    trigger_type TEXT NOT NULL DEFAULT 'cron',
    started_at TEXT NOT NULL,
    success INTEGER NOT NULL,
    error_message TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_task_exec_task ON task_executions(task_id, id);
