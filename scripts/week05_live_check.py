#!/usr/bin/env python3
"""Live check of the week-5 features (lessons 27 and 28) against the REAL executable JAR.

Starts the JAR in separate JVMs (loopback only, temporary files, the Mock model, a local weather
fixture, no network, no API key), drives it over HTTP, reads SQLite directly, stops the JVM and
starts it again to compare every table row by row.

    python scripts/week05_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar \
        --out docs/coursework/week-05/evidence/live-check.txt

Reuses the helpers of week04_live_check.py. Exit code 1 if any check failed.
"""
import argparse
import json
import shutil
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import week04_live_check as w4
from week04_live_check import Server, check, free_port, rows, say, section

HITS = []
WEATHER_BODY = '{"city":"Shanghai","temp_c":21,"sky":"clear"}'


class Weather(BaseHTTPRequestHandler):
    def do_GET(self):
        HITS.append(self.path)
        body = WEATHER_BODY.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def write_profiles(work, weather_url):
    prof = work / "profiles"
    prof.mkdir(parents=True)
    allowed = (work / "ws" / "allowed").as_posix()
    cron = '"0 0 9 1 1 *"'
    (prof / "agent-files.yaml").write_text(
        "name: agent-files\nprovider: {name: mock, model: m, temperature: 0.0}\n"
        "tools: [file_read, file_write, save_memory]\nsettings:\n  max_iterations: 5\n  sandbox:\n"
        f'    read_roots: ["{allowed}"]\n    write_roots: ["{allowed}"]\n'
        "schedules:\n"
        f"  - id: write-outside\n    cron: {cron}\n    zone: UTC\n"
        f"    message: 'tool:file_write {{\"path\":\"{(work / 'ws' / 'forbidden').as_posix()}/out.txt\",\"content\":\"x\"}}'\n"
    )
    (prof / "agent-web.yaml").write_text(
        "name: agent-web\nprovider: {name: mock, model: m, temperature: 0.0}\n"
        "tools: [http_get, recall_memory]\nsettings:\n  max_iterations: 5\n  sandbox:\n"
        '    hosts: ["127.0.0.1"]\n    allow_private_networks: true\n'
        "schedules:\n"
        f"  - id: weather\n    cron: {cron}\n    zone: Asia/Shanghai\n"
        f"    message: 'tool:http_get {{\"url\":\"{weather_url}\"}}'\n"
    )
    return prof


def dump(db):
    out = {}
    for table, order in [
        ("sessions", "session_id"), ("llm_calls", "id"), ("tool_invocations", "id"),
        ("scheduled_tasks", "task_id"), ("task_executions", "id"), ("memory_entries", "id"),
    ]:
        out[table] = rows(db, f"select * from {table} order by {order}")
    return out


def count(db, table, session):
    return rows(db, f"select count(*) from {table} where session_id = '{session}'")[0][0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out")
    args = ap.parse_args()
    jar = Path(args.jar).resolve()

    httpd = HTTPServer(("127.0.0.1", 0), Weather)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    weather_url = f"http://127.0.0.1:{httpd.server_port}/weather"

    base = Path(tempfile.mkdtemp(prefix="oryxos-week05-"))
    work = base / "w"
    (work / "ws" / "allowed").mkdir(parents=True)
    (work / "ws" / "forbidden").mkdir(parents=True)
    (work / "ws" / "allowed" / "a.txt").write_text("A-OWN-FILE")
    (work / "ws" / "forbidden" / "b-secret.txt").write_text("B-ONLY-SECRET")
    prof = write_profiles(work, weather_url)
    db = work / "live.db"
    extra = [
        "--oryxos.memory.backend=sqlite",
        "--oryxos.sandbox.hosts=127.0.0.1",
        "--oryxos.sandbox.allow-private-networks=true",
    ]
    say(f"JAR: {jar.name}, scratch dir: {base}")
    try:
        srv = Server(jar, work, free_port(), prof, db, extra)
        try:
            section("Lesson 27: weather and memory through the real JAR")
            s, b, _ = srv.post("/api/v1/agents/agent-web/invoke", {"message": f'tool:http_get {{"url":"{weather_url}"}}', "userId": "w1"})
            reply = b["data"]["reply"]
            session = "api:w1:agent-web"
            check("weather answer comes from the fixture", s == 200 and "Shanghai" in reply, reply)
            check("exactly one weather request", len(HITS) == 1, str(HITS))
            check("llm_calls: exactly 2 (tool request, final answer)", count(db, "llm_calls", session) == 2)
            check("tool_invocations: exactly 1, http_get, success", rows(db, f"select tool_name, success from tool_invocations where session_id='{session}'") == [("http_get", 1)])
            srv.invoke("agent-files", 'tool:save_memory {"content":"我喜欢喝美式咖啡","scope":"CORE"}', "m1")
            mem = srv.get("/api/v1/memory")[1]["data"]
            check("memory written and visible over REST", [e["content"] for e in mem["core"]] == ["我喜欢喝美式咖啡"])
            check("memory row in SQLite", rows(db, "select scope, content from memory_entries") == [("CORE", "我喜欢喝美式咖啡")])

            section("Lesson 28: isolation")
            a = f'tool:file_read {{"path":"{(work / "ws" / "allowed").as_posix()}/a.txt"}}'
            check("agent-files reads its own file", "A-OWN-FILE" in srv.invoke("agent-files", a, "i1")[1])
            secret = f'tool:file_read {{"path":"{(work / "ws" / "forbidden").as_posix()}/b-secret.txt"}}'
            r = srv.invoke("agent-files", secret, "i1")[1]
            check("agent-files cannot read outside its root", "SANDBOX_DENIED" in r and "B-ONLY-SECRET" not in r, r)
            check("agent-files has no http_get", srv.invoke("agent-files", f'tool:http_get {{"url":"{weather_url}"}}', "i2")[1] == "Tool not available: http_get")
            check("agent-web has no file_read", srv.invoke("agent-web", a, "i2")[1] == "Tool not available: file_read")
            check("fixture not touched by agent-files", len(HITS) == 1)
            sid = {x["sessionId"]: x["profileName"] for x in srv.get("/api/v1/sessions")[1]["data"]}
            check("same user, two agents, two sessions", sid.get("api:i1:agent-files") == "agent-files" and "api:i1:agent-web" not in sid)
            srv.invoke("agent-files", 'tool:save_memory {"content":"alpha-private-fact"}', "g1")
            check("memory is GLOBAL: agent-web reads what agent-files saved (per-agent isolation NOT implemented)",
                  "alpha-private-fact" in srv.invoke("agent-web", 'tool:recall_memory {"keyword":"alpha"}', "g2")[1])

            section("Lesson 28: scheduler, outcome, API")
            w = srv.post("/api/v1/schedules/agent-web.weather/run")[1]["data"]
            check("scheduled weather run succeeded through AgentService", w["success"] and w["sessionId"] == "scheduler:scheduler:agent-web")
            o = srv.get(f"/api/v1/schedules/agent-web.weather/executions/{w['id']}/outcome")[1]["data"]
            check("outcome COMPLETED, tool succeeded", o["outcome"] == "COMPLETED" and o["toolSucceeded"] == 1, json.dumps(o))
            d = srv.post("/api/v1/schedules/agent-files.write-outside/run")[1]["data"]
            check("denied scheduled run: technical success, file not written", d["success"] and not (work / "ws" / "forbidden" / "out.txt").exists())
            o = srv.get(f"/api/v1/schedules/agent-files.write-outside/executions/{d['id']}/outcome")[1]["data"]
            check("outcome COMPLETED_WITH_TOOL_ISSUES, not a plain success", o["outcome"] == "COMPLETED_WITH_TOOL_ISSUES" and o["toolDenied"] == 1, json.dumps(o))
            check("stored lastStatus unchanged (API compatible)", [t for t in srv.get("/api/v1/schedules")[1]["data"] if t["taskId"] == "agent-files.write-outside"][0]["lastStatus"] == "SUCCESS")
            srv.request("PUT", "/api/v1/schedules/agent-web.weather", {"enabled": False})
            check("disabled flag in SQLite", rows(db, "select enabled from scheduled_tasks where task_id='agent-web.weather'") == [(0,)])
            check("OpenAPI lists the outcome endpoint", any("outcome" in p for p in srv.get("/v3/api-docs")[1]["paths"]))
            check("health, swagger, prometheus, admin", srv.get("/api/v1/health")[0] == 200 and srv.get("/swagger-ui/index.html")[0] == 200
                  and srv.get("/actuator/prometheus")[0] == 200 and srv.get("/admin/")[0] == 200)
            before = dump(db)
            check("tables hold real rows before the restart", all(len(v) > 0 for v in before.values()),
                  ", ".join(f"{k}={len(v)}" for k, v in before.items()))
        finally:
            srv.stop()

        section("Restart (separate JVM, same files)")
        srv = Server(jar, work, free_port(), prof, db, extra)
        try:
            after = dump(db)
            for table in before:
                check(f"{table}: rows identical after restart", before[table] == after[table], f"{len(after[table])} rows")
            tasks = {t["taskId"]: t for t in srv.get("/api/v1/schedules")[1]["data"]}
            check("scheduler API shows the disabled task and run counts", tasks["agent-web.weather"]["enabled"] is False and tasks["agent-web.weather"]["runCount"] == 1)
            mem = srv.get("/api/v1/memory")[1]["data"]
            check("memory survives", [e["content"] for e in mem["core"]] == ["我喜欢喝美式咖啡"])
            check("sessions survive and keep growing", srv.invoke("agent-web", f'tool:http_get {{"url":"{weather_url}"}}', "w1")[0] == 200
                  and len(json.loads(rows(db, f"select messages_json from sessions where session_id='api:w1:agent-web'")[0][0])) == 8)
        finally:
            srv.stop()
    finally:
        httpd.shutdown()
        passed = sum(w4.RESULTS)
        say("")
        say(f"{passed}/{len(w4.RESULTS)} checks passed")
        if args.out:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text("\n".join(w4.TRANSCRIPT) + "\n", encoding="utf-8")
        shutil.rmtree(base, ignore_errors=True)
    return 0 if all(w4.RESULTS) else 1


if __name__ == "__main__":
    raise SystemExit(main())
