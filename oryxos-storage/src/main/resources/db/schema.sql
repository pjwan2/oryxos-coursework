-- SQLite-compatible schema, explicit migrations instead of Hibernate auto-update.
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
    occurred_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tool_session ON tool_invocations(session_id);
