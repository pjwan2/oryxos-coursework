#!/usr/bin/env python3
"""Live acceptance check of the week-4 features against the REAL executable JAR.

It starts the JAR as a separate JVM (loopback only, fresh temporary SQLite/Markdown files, the Mock
model, no network access, no API key) and drives Memory, Sandbox, Scheduler and the Web service
through HTTP, then reads the SQLite file / MEMORY.md directly to compare with what the API says.
Every check prints PASS or FAIL; the exit code is 1 if any check failed.

    python scripts/week04_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar \
        --out docs/coursework/week-04/evidence/live-check.txt

Needs only Python 3.9+ and Java 21. Nothing here is a substitute for `mvn clean verify`.
"""
import argparse
import json
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = []
TRANSCRIPT = []


def say(line):
    print(line, flush=True)
    TRANSCRIPT.append(line)


def check(name, ok, detail=""):
    RESULTS.append(bool(ok))
    say(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  -> {detail}" if detail else ""))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Server:
    def __init__(self, jar, work, port, profiles, db, extra=()):
        self.base = f"http://127.0.0.1:{port}"
        self.log = work / f"server-{port}-{int(time.time() * 1000)}.log"
        args = [
            "java", "-jar", str(jar), f"--server.port={port}", "--logging.level.root=WARN",
            f"--oryxos.profile-directory={profiles.as_posix()}",
            f"--spring.datasource.url=jdbc:sqlite:{db.as_posix()}",
            f"--oryxos.sandbox.read-roots={(work / 'ws' / 'allowed').as_posix()}",
            f"--oryxos.sandbox.write-roots={(work / 'ws' / 'allowed').as_posix()}",
            *extra,
        ]
        self.proc = subprocess.Popen(args, cwd=work, stdout=self.log.open("w"), stderr=subprocess.STDOUT)
        for _ in range(120):
            if self.proc.poll() is not None:
                raise RuntimeError("server exited early:\n" + self.log.read_text(errors="replace")[-2000:])
            try:
                if self.get("/api/v1/health")[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.5)
        raise RuntimeError("server did not become healthy")

    def request(self, method, path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        h = {"Content-Type": "application/json"} if body is not None else {}
        h.update(headers or {})
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw, status, hdrs = r.read(), r.status, r.headers
        except urllib.error.HTTPError as e:
            raw, status, hdrs = e.read(), e.code, e.headers
        text = raw.decode("utf-8", errors="replace")
        try:
            return status, json.loads(text), hdrs
        except ValueError:
            return status, text, hdrs

    def get(self, path, headers=None):
        return self.request("GET", path, None, headers)

    def post(self, path, body=None, headers=None):
        return self.request("POST", path, body if body is not None else {}, headers)

    def invoke(self, agent, message, user="live"):
        status, body, _ = self.post(f"/api/v1/agents/{agent}/invoke", {"message": message, "userId": user})
        return status, (body["data"]["reply"] if status == 200 else body)

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()


def rows(db, sql):
    con = sqlite3.connect(db)
    try:
        return con.execute(sql).fetchall()
    finally:
        con.close()


def profile_files(work):
    prof = work / "profiles"
    prof.mkdir(parents=True, exist_ok=True)
    for name in ("memory-demo.yaml", "payment-demo.yaml"):
        shutil.copy(REPO / ".oryxos" / "profiles" / name, prof / name)
    allowed = (work / "ws" / "allowed").as_posix()
    forbidden = (work / "ws" / "forbidden").as_posix()
    (prof / "ops.yaml").write_text(
        "name: ops\ndescription: sandbox and scheduler live check\n"
        "provider: {name: mock, model: mock-echo, temperature: 0.2}\n"
        "tools: [file_read, file_write, http_get]\n"
        "settings:\n  max_iterations: 5\n  sandbox:\n"
        f'    read_roots: ["{allowed}"]\n    write_roots: ["{allowed}"]\n    hosts: ["example.org"]\n'
        "schedules:\n"
        '  - id: report\n    cron: "0 0 3 1 1 *"\n    zone: Asia/Shanghai\n    message: "daily report"\n'
        '  - id: write-outside\n    cron: "0 0 4 1 1 *"\n    zone: UTC\n'
        f"    message: 'tool:file_write {{\"path\":\"{forbidden}/out.txt\",\"content\":\"x\"}}'\n"
    )
    (prof / "nosandbox.yaml").write_text(
        "name: nosandbox\ndescription: declares tools but no sandbox\n"
        "provider: {name: mock, model: mock-echo, temperature: 0.2}\ntools: [file_read]\n"
        "settings:\n  max_iterations: 5\n"
    )
    (prof / "flaky.yaml").write_text(
        "name: flaky\ndescription: every run fails inside the loop\n"
        "provider: {name: mock, model: mock-echo, temperature: 0.2}\ntools: []\n"
        'settings:\n  max_iterations: "not-a-number"\n'
    )
    return prof


def section(title):
    say("")
    say(f"== {title}")


def check_memory(jar, base):
    for backend in ("markdown", "sqlite"):
        section(f"Memory, backend={backend} (lessons 21/22)")
        work = base / f"mem-{backend}"
        (work / "ws" / "allowed").mkdir(parents=True)
        prof = profile_files(work)
        db = work / "live.db"
        md = work / "MEMORY.md"
        extra = [f"--oryxos.memory.backend={backend}", f"--oryxos.memory.markdown-path={md.as_posix()}"]
        srv = Server(jar, work, free_port(), prof, db, extra)
        try:
            _, r1 = srv.invoke("memory-demo", 'tool:save_memory {"content":"Alex prefers green tea","scope":"CORE"}', "u1")
            check("save CORE", "CORE" in r1, r1)
            _, r2 = srv.invoke("memory-demo", 'tool:save_memory {"content":"met Sam at the harbour"}', "u1")
            check("save defaults to ARCHIVAL", "ARCHIVAL" in r2, r2)
            _, r3 = srv.invoke("memory-demo", 'tool:recall_memory {"keyword":"HARBOUR"}', "another-user")
            check("recall (case-insensitive) from a NEW user sees it", "met Sam at the harbour" in r3, r3)
            _, r4 = srv.invoke("memory-demo", 'tool:recall_memory {"keyword":"Alex"}', "another-user")
            check("recall searches ARCHIVAL only, not CORE", "No matching" in r4, r4)
            _, r5 = srv.invoke("memory-demo", 'tool:save_memory {"content":"x","scope":"FOREVER"}', "u1")
            check("invalid scope is rejected", "ERROR" in r5 and "scope" in r5, r5)
            _, r6 = srv.invoke("payment-demo", 'tool:save_memory {"content":"sneaky"}', "u1")
            check("agent without the tool in its Profile cannot use memory", "not available" in r6, r6)

            mem = srv.get("/api/v1/memory")[1]["data"]
            check("REST /memory reports the backend", mem["backend"] == backend, mem["backend"])
            check("REST /memory lists CORE and ARCHIVAL", [e["content"] for e in mem["core"]] == ["Alex prefers green tea"]
                  and [e["content"] for e in mem["archival"]] == ["met Sam at the harbour"])
            if backend == "markdown":
                text = md.read_text(encoding="utf-8")
                check("MEMORY.md has both sections with the entries",
                      "## 核心记忆" in text and "## 归档记忆" in text and "Alex prefers green tea" in text
                      and "met Sam at the harbour" in text and "FOREVER" not in text)
                check("SQLite memory table untouched by the markdown backend",
                      rows(db, "select count(*) from memory_entries")[0][0] == 0)
            else:
                got = rows(db, "select scope, content from memory_entries order by id")
                check("memory_entries rows match the API", got == [("CORE", "Alex prefers green tea"), ("ARCHIVAL", "met Sam at the harbour")], str(got))
                check("no MEMORY.md written by the sqlite backend", not md.exists())
        finally:
            srv.stop()
        srv = Server(jar, work, free_port(), prof, db, extra)
        try:
            mem = srv.get("/api/v1/memory")[1]["data"]
            check("memory survives a JVM restart", len(mem["core"]) == 1 and len(mem["archival"]) == 1)
        finally:
            srv.stop()


def check_sandbox(srv, work, db):
    section("Sandbox (lessons 23/24)")
    allowed = (work / "ws" / "allowed").as_posix()
    forbidden = (work / "ws" / "forbidden").as_posix()
    (work / "ws" / "allowed" / "hello.txt").write_text("HELLO-FROM-ALLOWED")
    (work / "ws" / "forbidden" / "secret.txt").write_text("TOP-SECRET")

    _, r = srv.invoke("ops", f'tool:file_read {{"path":"{allowed}/hello.txt"}}')
    check("read inside the declared root is allowed", "HELLO-FROM-ALLOWED" in r, r)
    _, r = srv.invoke("ops", f'tool:file_read {{"path":"{forbidden}/secret.txt"}}')
    check("read outside the root is denied and leaks nothing", "SANDBOX_DENIED" in r and "TOP-SECRET" not in r, r)
    _, r = srv.invoke("ops", f'tool:file_read {{"path":"{allowed}/../forbidden/secret.txt"}}')
    check("../ traversal is denied", "SANDBOX_DENIED" in r and "TOP-SECRET" not in r, r)
    _, r = srv.invoke("ops", f'tool:file_write {{"path":"{forbidden}/pwned.txt","content":"x"}}')
    check("write outside the root is denied and the file is NOT created",
          "SANDBOX_DENIED" in r and not (work / "ws" / "forbidden" / "pwned.txt").exists(), r)
    _, r = srv.invoke("ops", f'tool:file_write {{"path":"{allowed}/note.txt","content":"hello"}}')
    check("write inside the root works", (work / "ws" / "allowed" / "note.txt").read_text() == "hello", r)
    _, r = srv.invoke("ops", 'tool:http_get {"url":"http://169.254.169.254/latest/meta-data/"}')
    check("cloud metadata address is never allowed", "SANDBOX_DENIED" in r, r)
    _, r = srv.invoke("ops", 'tool:http_get {"url":"http://2130706433/"}')
    check("numeric-disguised loopback address is denied", "SANDBOX_DENIED" in r, r)
    _, r = srv.invoke("ops", 'tool:http_get {"url":"http://evil.test/"}')
    check("host not in the Profile's list is denied", "SANDBOX_DENIED" in r, r)
    _, r = srv.invoke("nosandbox", f'tool:file_read {{"path":"{allowed}/hello.txt"}}')
    check("Profile with no sandbox declaration is denied everything", "SANDBOX_DENIED" in r, r)

    got = rows(db, "select tool_name, success, error_category from tool_invocations order by id")
    denied = [g for g in got if g[2] == "SandboxDenied"]
    check("every refusal is audited as SandboxDenied", len(denied) == 7, f"{len(denied)} of {len(got)} rows")
    api = srv.get("/api/v1/tool-calls?limit=200")[1]["data"]
    check("REST /tool-calls equals the audit table", len(api) == len(got))
    check("audit rows carry a safe readable reason", all(c["errorMessage"] for c in api if c["errorCategory"] == "SandboxDenied")
          and not any("169.254" in (c["errorMessage"] or "") for c in api))
    for line in got:
        say(f"      audit: {line}")


def check_scheduler(jar, work, prof, db):
    section("Scheduler (lesson 25)")
    srv = Server(jar, work, free_port(), prof, db)
    try:
        tasks = {t["taskId"]: t for t in srv.get("/api/v1/schedules")[1]["data"]}
        check("tasks from Profile YAML are registered, enabled, never run",
              set(tasks) == {"ops.report", "ops.write-outside"} and all(t["enabled"] and t["runCount"] == 0 for t in tasks.values()))
        check("time zone is kept per task", tasks["ops.report"]["zone"] == "Asia/Shanghai" and tasks["ops.write-outside"]["zone"] == "UTC")
        check("next run is computed", all(t["nextRunAt"] for t in tasks.values()))
        status, run, _ = srv.post("/api/v1/schedules/ops.report/run")
        e = run["data"]
        check("manual run succeeds through the shared AgentService", status == 200 and e["success"] and e["sessionId"] == "scheduler:scheduler:ops", json.dumps(e))
        status, run, _ = srv.post("/api/v1/schedules/ops.write-outside/run")
        check("run whose tool is refused: technical success, sandbox denial audited separately",
              run["data"]["success"] and not (work / "ws" / "forbidden" / "out.txt").exists())
        _, body, _ = srv.get("/api/v1/tool-calls?sessionId=scheduler:scheduler:ops&limit=10")
        check("the scheduled call was denied by the sandbox in the audit", any(c["toolName"] == "file_write" and c["errorCategory"] == "SandboxDenied" for c in body["data"]))
        status, f, _ = srv.post("/api/v1/schedules/ops.report/run")
        check("unknown task is 404", srv.post("/api/v1/schedules/nope.nope/run")[0] == 404)
        check("disable through the API", srv.request("PUT", "/api/v1/schedules/ops.report", {"enabled": False})[1]["data"]["enabled"] is False)
        status, run, _ = srv.post("/api/v1/schedules/ops.report/run")
        check("manual run is still allowed while disabled, and does not re-enable",
              status == 200 and run["data"]["success"]
              and [t for t in srv.get("/api/v1/schedules")[1]["data"] if t["taskId"] == "ops.report"][0]["enabled"] is False)
        check("invalid cron is rejected with 400", srv.request("PUT", "/api/v1/schedules/ops.write-outside", {"cron": "bogus"})[0] == 400)
        before = rows(db, "select task_id, enabled, run_count, last_status from scheduled_tasks order by task_id")
        history_before = rows(db, "select id, task_id, trigger_type, success from task_executions order by id")
        say(f"      scheduled_tasks before restart: {before}")
    finally:
        srv.stop()
    srv = Server(jar, work, free_port(), prof, db)
    try:
        after = rows(db, "select task_id, enabled, run_count, last_status from scheduled_tasks order by task_id")
        check("after a JVM restart state, run counts and enabled flags are unchanged", after == before, str(after))
        tasks = {t["taskId"]: t for t in srv.get("/api/v1/schedules")[1]["data"]}
        check("API shows the disabled task and its runs", tasks["ops.report"]["enabled"] is False and tasks["ops.report"]["runCount"] == 3)
        hist = srv.get("/api/v1/schedules/ops.report/executions")[1]["data"]
        check("execution history survives the restart", len(hist) == 3 and rows(db, "select id, task_id, trigger_type, success from task_executions order by id") == history_before)
        migrations = [r[0] for r in rows(db, "select version from schema_migrations order by version")]
        check("schema_migrations recorded versions 1..4 (V4 added in week 5)", migrations == [1, 2, 3, 4], str(migrations))
    finally:
        srv.stop()


def check_web(srv):
    section("Web service, Swagger, Prometheus, admin console (lesson 26)")
    s, b, h = srv.get("/api/v1/health")
    check("GET /health 200 with ok + trace id", s == 200 and b["data"]["status"] == "ok" and h["X-Trace-Id"], h["X-Trace-Id"])
    check("GET /info", srv.get("/api/v1/info")[1]["data"]["bindAddress"] == "127.0.0.1")
    check("GET /profiles", {p["name"] for p in srv.get("/api/v1/profiles")[1]["data"]} >= {"ops", "memory-demo"})
    check("GET /tools has schemas", all(isinstance(t["inputSchema"], dict) for t in srv.get("/api/v1/tools")[1]["data"]))
    s, b, _ = srv.post("/api/v1/sessions", {"profile": "ops", "userId": "w1"})
    check("POST /sessions -> 201", s == 201 and b["data"]["sessionId"] == "web:w1:ops")
    check("POST /sessions again -> 200 (idempotent)", srv.post("/api/v1/sessions", {"profile": "ops", "userId": "w1"})[0] == 200)
    s, b, _ = srv.post("/api/v1/sessions/web:w1:ops/messages", {"content": "hello"})
    check("POST /sessions/{id}/messages", s == 200 and b["data"]["reply"] == "Mock response: hello")
    d = srv.get("/api/v1/sessions/web:w1:ops")[1]["data"]
    check("GET /sessions/{id} returns the history", d["totalMessages"] == 2 and d["messages"][0]["role"] == "USER")
    lst = srv.get("/api/v1/sessions")[1]["data"]
    check("GET /sessions is newest-first and capped at 100", len(lst) <= 100 and lst[0]["sessionId"] == "web:w1:ops")
    check("DELETE /sessions/{id}", srv.request("DELETE", "/api/v1/sessions/web:w1:ops")[0] == 200 and srv.get("/api/v1/sessions/web:w1:ops")[0] == 404)

    check("400: missing profile", srv.post("/api/v1/sessions", {})[0] == 400)
    check("400: blank message", srv.post("/api/v1/agents/ops/invoke", {"message": "  "})[0] == 400)
    check("400: message over 32 KiB", srv.post("/api/v1/agents/ops/invoke", {"message": "x" * 32769})[0] == 400)
    check("400: malformed JSON", srv.request("POST", "/api/v1/agents/ops/invoke", None, {"Content-Type": "application/json"})[0] in (400,))
    check("404: unknown session", srv.get("/api/v1/sessions/web:nobody:ops")[0] == 404)
    check("404: unknown agent", srv.post("/api/v1/agents/ghost/invoke", {"message": "hi"})[0] == 404)
    s, b, _ = srv.post("/api/v1/agents/flaky/invoke", {"message": "hi"})
    check("500 returns a fixed text and leaks nothing", s == 500 and b["message"] == "Internal server error" and "max_iterations" not in json.dumps(b), json.dumps(b)[:120])
    check("403: Host header of a foreign name (DNS rebinding)", srv.get("/api/v1/sessions", {"Host": "evil.example"})[0] == 403)
    check("403: forwarded request", srv.get("/api/v1/memory", {"X-Forwarded-For": "1.2.3.4"})[0] == 403)
    check("403: cross-site Origin on a write", srv.request("POST", "/api/v1/schedules/ops.report/run", {}, {"Origin": "https://evil.example"})[0] == 403)
    s, b, _ = srv.get("/api/v1/does-not-exist")
    check("unknown API path is a JSON 404", s == 404 and isinstance(b, dict) and b["code"] == 404)
    s, b, h = srv.get("/v3/api-docs")
    check("OpenAPI document is served", s == 200 and len(b["paths"]) >= 10, f"{len(b['paths'])} paths")
    s, _, _ = srv.get("/swagger-ui/index.html")
    check("Swagger UI is served", s == 200)
    s, b, _ = srv.get("/actuator/prometheus")
    check("Prometheus endpoint exposes metrics", s == 200 and "jvm_memory_used_bytes" in b)
    s, b, h = srv.get("/admin/")
    check("/admin/ serves the Vue console from the JAR", s == 200 and '<div id="app"></div>' in b and "/admin/assets/" in b)
    for route in ("/admin/sessions", "/admin/scheduler", "/admin/sessions/web:a:b"):
        s, b, _ = srv.get(route)
        check(f"client route {route} reloads (no 404)", s == 200 and '<div id="app"></div>' in b)
    check("missing asset stays 404", srv.get("/admin/assets/nope.js")[0] == 404)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out", help="write the transcript here")
    args = ap.parse_args()
    jar = Path(args.jar).resolve()
    base = Path(tempfile.mkdtemp(prefix="oryxos-week04-"))
    say(f"JAR: {jar.name} ({jar.stat().st_size // 1024} KiB), scratch dir: {base}")
    try:
        check_memory(jar, base)
        work = base / "main"
        (work / "ws" / "allowed").mkdir(parents=True)
        (work / "ws" / "forbidden").mkdir(parents=True)
        prof = profile_files(work)
        db = work / "live.db"
        srv = Server(jar, work, free_port(), prof, db)
        try:
            check_sandbox(srv, work, db)
            check_web(srv)
        finally:
            srv.stop()
        check_scheduler(jar, work, prof, db)
    finally:
        passed = sum(RESULTS)
        say("")
        say(f"{passed}/{len(RESULTS)} checks passed")
        if args.out:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text("\n".join(TRANSCRIPT) + "\n", encoding="utf-8")
        shutil.rmtree(base, ignore_errors=True)
    return 0 if all(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
