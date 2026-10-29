import sqlite3, sys, json
con = sqlite3.connect(sys.argv[1] + "/b4.db"); con.row_factory = sqlite3.Row
def q(sql): return [dict(r) for r in con.execute(sql)]
print("sessions:", [(r['session_id'], len(json.loads(r['messages_json']))) for r in con.execute("select session_id, messages_json from sessions order by updated_at desc")])
print("tool_invocations:"); [print("  ", r) for r in q("select session_id, tool_name, success, error_category, error_message from tool_invocations order by id")]
print("memory_entries:", q("select scope, content from memory_entries"))
print("scheduled_tasks:"); [print("  ", r) for r in q("select task_id, enabled, run_count, last_status from scheduled_tasks order by task_id")]
print("task_executions:"); [print("  ", r) for r in q("select id, task_id, trigger_type, success, error_message from task_executions order by id")]
print("schema_migrations:", [r['version'] for r in con.execute("select version from schema_migrations order by version")])
