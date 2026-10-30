"""Lesson 30 live checks against the REAL executable JAR (called from week05_agents_live_check.py).

A temporary workspace, the offline Mock model, a local OpenAI-compatible stub for the draft call,
loopback only, no network, no key. The agents directory is watched by the real WorkspaceWatcher.
"""
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from week04_live_check import Server, check, free_port, rows, say, section

DRAFT = (
    "<<<FILE AGENT.md>>>\n---\nname: beta\ndescription: Drafted by the stub model\n"
    "provider: {name: fake-openai, model: fake-model}\ntools: [save_memory, recall_memory]\n---\n"
    "Drafted instructions DRAFT-MARKER\n<<<END>>>\n<<<FILE REFERENCE.md>>>\nDrafted reference\n<<<END>>>"
)
PROMPTS = []


class StubModel(BaseHTTPRequestHandler):
    """Speaks the OpenAI chat protocol: the draft for a generation prompt, an echo otherwise."""

    def do_POST(self):
        body = json.loads(self.read_body())
        system = next((m["content"] for m in body["messages"] if m["role"] == "system"), "")
        PROMPTS.append(system)
        text = DRAFT if "You write the definition of an AI agent" in system else "stub model says hi"
        payload = json.dumps({
            "id": "x", "object": "chat.completion", "created": 0, "model": "fake-model",
            "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": text}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def read_body(self):
        """The JDK HTTP client may send the body chunked, without a Content-Length."""
        if self.headers.get("Content-Length"):
            return self.rfile.read(int(self.headers["Content-Length"]))
        data = b""
        while True:
            size = int(self.rfile.readline().strip().split(b";")[0], 16)
            if size == 0:
                self.rfile.readline()
                return data
            data += self.rfile.read(size)
            self.rfile.readline()

    def log_message(self, *args):
        pass


def md(description, tools, body, extra="", provider="mock", model="mock-echo"):
    return (f"---\ndescription: {description}\nprovider: {{name: {provider}, model: {model}}}\n"
            f"tools: {tools}\n{extra}---\n{body}\n")


def eventually(what, condition, seconds=20):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if condition():
            return True
        time.sleep(0.25)
    say(f"      (timed out waiting for: {what})")
    return False


def dump(db):
    return {t: rows(db, f"select * from {t} order by {o}") for t, o in [
        ("sessions", "session_id"), ("llm_calls", "id"), ("tool_invocations", "id"),
        ("scheduled_tasks", "task_id"), ("task_executions", "id"), ("memory_entries", "id")]}


def stage_c4(jar, base):
    stub = HTTPServer(("127.0.0.1", 0), StubModel)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    work = base / "c4"
    work.mkdir()
    allowed = work / "ws" / "allowed"
    forbidden = work / "ws" / "forbidden"
    allowed.mkdir(parents=True)
    forbidden.mkdir(parents=True)
    (allowed / "ledger.txt").write_text("LEDGER-CONTENT")
    profiles = work / "profiles"
    profiles.mkdir()
    (profiles / "legacy.yaml").write_text(
        "name: legacy\ndescription: old style\nprovider: {name: mock, model: m, temperature: 0.0}\n"
        "tools: [save_memory, recall_memory]\n")
    agents = work / "agents"
    archive = work / "archive"
    db = work / "live.db"
    memory_file = work / "global-MEMORY.md"
    extra = [
        f"--oryxos.agents-directory={agents.as_posix()}",
        f"--oryxos.archive-directory={archive.as_posix()}",
        f"--oryxos.memory.markdown-path={memory_file.as_posix()}",
        "--oryxos.agents-watch-debounce-millis=300",
        "--oryxos.providers[0].name=mock",
        "--oryxos.providers[0].type=mock",
        "--oryxos.providers[0].model=mock-echo",
        "--oryxos.providers[1].name=fake-openai",
        "--oryxos.providers[1].type=openai-compatible",
        "--oryxos.providers[1].model=fake-model",
        f"--oryxos.providers[1].base-url=http://127.0.0.1:{stub.server_port}",
        "--oryxos.providers[1].api-key=dummy-key-not-a-secret",
    ]
    srv = Server(jar, work, free_port(), profiles, db, extra)
    try:
        section("Lesson 30: create, read, update (REST)")
        s, b, _ = srv.post("/api/v1/agents", {"name": "alpha", "description": "First agent"})
        check("POST /agents creates an agent from the template", s == 201 and b["data"]["name"] == "alpha", str(b)[:120])
        check("AGENT.md exists on disk", (agents / "alpha" / "AGENT.md").is_file())
        s, b, _ = srv.get("/api/v1/agents")
        names = {a["name"]: a for a in b["data"]["agents"]}
        check("GET /agents lists it with the legacy profile", {"alpha", "legacy"} <= set(names), str(sorted(names)))
        check("legacy profile is marked read-only", names["legacy"]["editable"] is False and names["legacy"]["origin"] == "legacy-yaml")
        check("duplicate name -> 409", srv.post("/api/v1/agents", {"name": "alpha", "description": "x"})[0] == 409)
        check("legacy name -> 409", srv.post("/api/v1/agents", {"name": "legacy", "description": "x"})[0] == 409)
        check("path-like name -> 400", srv.post("/api/v1/agents", {"name": "../x", "description": "x"})[0] == 400)
        check("unknown agent -> 404", srv.get("/api/v1/agents/nobody")[0] == 404)

        section("Lesson 30: dynamic schedule update and execution id")
        sched = ('schedules:\n  - {id: nightly, cron: "0 0 2 1 1 *", zone: UTC, message: "tool:file_read '
                 f'{{\\"path\\":\\"{(allowed / "ledger.txt").as_posix()}\\"}}"}}\n')
        sandbox = f'settings:\n  sandbox:\n    read_roots: ["{allowed.as_posix()}"]\n'
        v2 = md("Second version", "[file_read, save_memory, recall_memory]", "You are alpha. ALPHA-V2", sched + sandbox)
        s, b, _ = srv.request("PUT", "/api/v1/agents/alpha", {"agentMd": v2})
        check("PUT /agents/alpha applies the new definition", s == 200 and b["data"]["description"] == "Second version", str(b)[:160])
        tasks = {t["taskId"]: t for t in srv.get("/api/v1/schedules")[1]["data"]}
        check("schedule registered while running", tasks.get("alpha.nightly", {}).get("cron") == "0 0 2 1 1 *")
        s, b, _ = srv.post("/api/v1/schedules/alpha.nightly/run")
        execution = b["data"]
        check("manual run succeeds and carries an execution id", s == 200 and execution["success"] and execution.get("executionId"), str(execution)[:200])
        o = srv.get(f"/api/v1/schedules/alpha.nightly/executions/{execution['id']}/outcome")[1]["data"]
        check("outcome is matched by execution id", o["matchedBy"] == "execution_id" and o["toolSucceeded"] == 1 and o["outcome"] == "COMPLETED", str(o)[:200])
        tool_rows = rows(db, f"select tool_name, success, execution_id from tool_invocations where execution_id = '{execution['executionId']}'")
        check("tool_invocations row carries the same id", tool_rows == [("file_read", 1, execution["executionId"])], str(tool_rows))
        check("task_executions row carries the id", rows(db, "select execution_id from task_executions") == [(execution["executionId"],)])
        v3 = v2.replace("0 0 2 1 1 *", "0 30 3 1 1 *")
        srv.request("PUT", "/api/v1/agents/alpha", {"agentMd": v3})
        tasks = {t["taskId"]: t for t in srv.get("/api/v1/schedules")[1]["data"]}
        check("changed cron replaces the old schedule (one task, new cron, history kept)",
              list(t for t in tasks if t.startswith("alpha.")) == ["alpha.nightly"] and tasks["alpha.nightly"]["cron"] == "0 30 3 1 1 *" and tasks["alpha.nightly"]["runCount"] == 1)
        srv.request("PUT", "/api/v1/agents/alpha", {"agentMd": md("Second version", "[file_read, save_memory, recall_memory]", "You are alpha. ALPHA-V2", sandbox)})
        check("removing the schedule stops the task", not any(t["taskId"].startswith("alpha.") for t in srv.get("/api/v1/schedules")[1]["data"]))
        check("its history is still readable", len(srv.get("/api/v1/schedules/alpha.nightly/executions")[1]["data"]) == 1)
        before = (agents / "alpha" / "AGENT.md").read_text()
        s, b, _ = srv.request("PUT", "/api/v1/agents/alpha", {"agentMd": md("bad", "[teleport]", "x")})
        check("unknown tool is refused with a readable message", s == 400 and "tool 'teleport' is not registered" in b["message"], str(b)[:160])
        check("invalid update left the file untouched", (agents / "alpha" / "AGENT.md").read_text() == before)

        section("Lesson 30: sandbox permission tightening takes effect on the next call")
        read = f'tool:file_read {{"path":"{(allowed / "ledger.txt").as_posix()}"}}'
        check("allowed read works", "LEDGER-CONTENT" in srv.invoke("alpha", read, "t1")[1])
        srv.request("PUT", "/api/v1/agents/alpha", {"agentMd": md("Tight", "[file_read, save_memory, recall_memory]", "You are alpha.", "settings:\n  sandbox:\n    read_roots: []\n")})
        denied = srv.invoke("alpha", read, "t1")[1]
        check("same call is denied right after the change", "SANDBOX_DENIED" in denied and "LEDGER-CONTENT" not in denied, denied)
        check("denial audited as SandboxDenied", rows(db, "select success, error_category from tool_invocations where session_id='api:t1:alpha' order by id") == [(1, None), (0, "SandboxDenied")])

        section("Lesson 30: per-agent memory")
        srv.post("/api/v1/agents", {"name": "beta", "description": "Second agent"})
        srv.request("PUT", "/api/v1/agents/beta", {"agentMd": md("Beta", "[save_memory, recall_memory]", "You are beta.")})
        srv.invoke("alpha", 'tool:save_memory {"content":"alpha loves espresso","scope":"CORE"}', "m1")
        srv.invoke("beta", 'tool:save_memory {"content":"beta loves tea","scope":"CORE"}', "m1")
        srv.invoke("legacy", 'tool:save_memory {"content":"legacy shared fact","scope":"CORE"}', "m1")
        a_mem = (agents / "alpha" / "MEMORY.md").read_text(encoding="utf-8")
        b_mem = (agents / "beta" / "MEMORY.md").read_text(encoding="utf-8")
        check("each agent writes its own MEMORY.md", "espresso" in a_mem and "tea" not in a_mem and "tea" in b_mem and "espresso" not in b_mem)
        check("legacy profile still uses the global file", "legacy shared fact" in memory_file.read_text(encoding="utf-8"))
        check("global file has no agent's private fact", "espresso" not in memory_file.read_text(encoding="utf-8") and "tea" not in memory_file.read_text(encoding="utf-8"))
        check("beta cannot recall alpha's fact", "No matching archived memories" in srv.invoke("beta", 'tool:recall_memory {"keyword":"espresso"}', "m2")[1])
        m = srv.get("/api/v1/agents/alpha/memory")[1]["data"]
        check("GET /agents/alpha/memory is private", m["privateMemory"] and [e["content"] for e in m["core"]] == ["alpha loves espresso"])
        lm = srv.get("/api/v1/agents/legacy/memory")[1]["data"]
        check("GET /agents/legacy/memory says it is shared", lm["shared"] and [e["content"] for e in lm["core"]] == ["legacy shared fact"])

        section("Lesson 30: console session, concurrency")
        r1 = srv.post("/api/v1/agents/alpha/session/messages", {"message": "hello"})
        check("console message gets a reply", r1[0] == 200 and r1[1]["data"]["reply"] == "Mock response: hello" and r1[1]["data"]["sessionId"] == "admin:console:alpha")
        results = []
        threads = [threading.Thread(target=lambda i=i: results.append(srv.post("/api/v1/agents/alpha/session/messages", {"message": f"parallel-{i}"})[0])) for i in range(6)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        sess = srv.get("/api/v1/agents/alpha/session")[1]["data"]
        users = [m["content"] for m in sess["messages"] if m["role"] == "USER"]
        replies = [m["content"] for m in sess["messages"] if m["role"] == "ASSISTANT"]
        check("six simultaneous messages all succeeded", results == [200] * 6, str(results))
        check("no message or answer was lost (7 user + 7 assistant)", len(users) == 7 and len(replies) == 7, f"{len(users)}/{len(replies)}")
        check("every question is followed by its own answer", all(sess["messages"][i + 1]["content"] == "Mock response: " + sess["messages"][i]["content"] for i in range(0, len(sess["messages"]), 2)))

        section("Lesson 30: generate (draft) then confirm")
        srv.request("PUT", "/api/v1/agents/beta", {"agentMd": md("Beta", "[save_memory, recall_memory]", "You are beta.", provider="fake-openai", model="fake-model")})
        beta_before = (agents / "beta" / "AGENT.md").read_text()
        s, b, _ = srv.post("/api/v1/agents/beta/generate-files", {"description": "a tea agent"})
        draft = b["data"]
        check("draft is valid and has two files", s == 200 and draft["valid"] and [f["path"] for f in draft["files"]] == ["AGENT.md", "REFERENCE.md"], str(b)[:200])
        check("generating saved nothing", (agents / "beta" / "AGENT.md").read_text() == beta_before and not (agents / "beta" / "REFERENCE.md").exists())
        check("the draft call is audited in llm_calls", rows(db, "select count(*) from llm_calls where session_id='admin:generator:beta'")[0][0] == 1)
        check("the prompt and key are not in the response", "You write the definition" not in json.dumps(b) and "dummy-key" not in json.dumps(b))
        s, b, _ = srv.post("/api/v1/agents/beta/files", {"files": draft["files"]})
        check("confirmed draft is saved and live", s == 200 and b["data"]["description"] == "Drafted by the stub model" and (agents / "beta" / "REFERENCE.md").is_file(), str(b)[:160])

        section("Lesson 30: workspace API")
        tree = [e["path"] for e in srv.get("/api/v1/workspace/tree")[1]["data"]]
        check("tree lists agent files", "alpha/AGENT.md" in tree and "beta/REFERENCE.md" in tree)
        for bad in ["../live.db", "/etc/passwd", "alpha/../beta/AGENT.md", "C:/Windows/win.ini", "alpha\\AGENT.md"]:
            check(f"read {bad!r} is refused", srv.get("/api/v1/workspace/file?path=" + urllib_quote(bad))[0] == 400)
        check("MEMORY.md is readable", srv.get("/api/v1/workspace/file?path=alpha/MEMORY.md")[0] == 200)
        check("MEMORY.md is not writable", srv.post("/api/v1/workspace/file", {"path": "alpha/MEMORY.md", "content": "wiped"})[0] == 400)
        s, b, _ = srv.post("/api/v1/workspace/file", {"path": "alpha/AGENT.md", "content": "no frontmatter"})
        check("saving an invalid AGENT.md is refused and keeps the agent", s == 400 and srv.get("/api/v1/agents/alpha")[1]["data"]["description"] == "Tight")

        section("Lesson 30: file watcher (manual copy, edit, delete, bad directory)")
        (agents / "gamma").mkdir()
        (agents / "gamma" / "AGENT.md").write_text(md("Gamma v1", "[]", "G1"), encoding="utf-8")
        check("copied directory is registered by the watcher", eventually("gamma", lambda: srv.get("/api/v1/agents/gamma")[0] == 200))
        (agents / "gamma" / "AGENT.md").write_text(md("Gamma v2", "[]", "G2"), encoding="utf-8")
        check("edited AGENT.md is reloaded", eventually("gamma v2", lambda: srv.get("/api/v1/agents/gamma")[1]["data"].get("description") == "Gamma v2"))
        (agents / "delta").mkdir()
        (agents / "delta" / "AGENT.md").write_text("no frontmatter", encoding="utf-8")
        check("a bad directory is reported", eventually("delta problem", lambda: "delta" in srv.get("/api/v1/agents")[1]["data"]["problems"]))
        check("the other agents are unaffected", srv.get("/api/v1/agents/gamma")[0] == 200 and srv.get("/api/v1/agents/beta")[0] == 200)
        (agents / "gamma" / "AGENT.md").write_text("---\ndescription: [half written\n", encoding="utf-8")
        check("an unfinished/invalid edit keeps the previous version", eventually("gamma problem", lambda: "gamma" in srv.get("/api/v1/agents")[1]["data"]["problems"]) and srv.get("/api/v1/agents/gamma")[1]["data"]["description"] == "Gamma v2")
        import shutil
        shutil.rmtree(agents / "gamma")
        check("deleted directory retires the agent", eventually("gamma gone", lambda: srv.get("/api/v1/agents/gamma")[0] == 404))

        section("Lesson 30: local-only protection")
        cross = srv.request("POST", "/api/v1/agents", {"name": "evil", "description": "x"}, {"Origin": "https://evil.example"})
        check("cross-site POST is refused", cross[0] == 403 and not (agents / "evil").exists())
        check("rebinding Host header is refused", srv.get("/api/v1/agents", {"Host": "evil.example"})[0] == 403)
        lan = [ip for ip in socket.gethostbyname_ex(socket.gethostname())[2] if not ip.startswith("127.")]
        if lan:
            port = int(srv.base.rsplit(":", 1)[1])
            try:
                socket.create_connection((lan[0], port), timeout=2).close()
                reachable = True
            except OSError:
                reachable = False
            check(f"the server is not reachable on the LAN address {lan[0]}", not reachable)
        else:
            say("      (no non-loopback address on this machine: LAN reachability not checked)")

        section("Lesson 30: archive, then restart")
        alpha_sessions = rows(db, "select count(*) from llm_calls where session_id='admin:console:alpha'")[0][0]
        s, b, _ = srv.request("DELETE", "/api/v1/agents/alpha")
        location = b["data"]["location"]
        check("DELETE archives (does not delete)", s == 200 and location.startswith("archive/alpha-") and (work / location).is_dir())
        check("private memory moved with the agent", "espresso" in (work / location / "MEMORY.md").read_text(encoding="utf-8") and not (agents / "alpha").exists())
        check("archived agent is gone from the API", srv.get("/api/v1/agents/alpha")[0] == 404)
        check("its audit history is still in SQLite", rows(db, "select count(*) from llm_calls where session_id='admin:console:alpha'")[0][0] == alpha_sessions > 0)
        check("legacy profile cannot be archived", srv.request("DELETE", "/api/v1/agents/legacy")[0] == 409)
        before = dump(db)
        beta_files = {p.name: p.read_text(encoding="utf-8") for p in (agents / "beta").iterdir() if p.is_file()}
    finally:
        srv.stop()

    srv2 = Server(jar, work, free_port(), profiles, db, extra)
    try:
        names = {a["name"] for a in srv2.get("/api/v1/agents")[1]["data"]["agents"]}
        check("after restart: beta and legacy are back, alpha is not", {"beta", "legacy"} <= names and "alpha" not in names, str(sorted(names)))
        check("after restart: all six tables are identical", dump(db) == before)
        check("after restart: beta's files are identical", {p.name: p.read_text(encoding="utf-8") for p in (agents / "beta").iterdir() if p.is_file()} == beta_files)
        mem = srv2.get("/api/v1/agents/beta/memory")[1]["data"]
        check("after restart: beta's private memory is intact", [e["content"] for e in mem["core"]] == ["beta loves tea"])
        check("after restart: beta still answers", srv2.invoke("beta", "ping", "r1")[0] == 200)

        section("Lesson 30: the admin console in a real browser (headless Chromium via Playwright)")
        browser_checks(srv2, agents)
    finally:
        srv2.stop()


def urllib_quote(text):
    from urllib.parse import quote
    return quote(text, safe="/")


def browser_checks(srv, agents):
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        say("      (playwright is not installed: browser interaction not checked)")
        return
    with sync_playwright() as p:
        try:
            browser = p.chromium.launch(channel="chrome")
        except Exception:
            browser = p.chromium.launch()
        page = browser.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        page.goto(srv.base + "/admin/agents")
        page.wait_for_selector("[data-test=agent]")
        cards = page.locator("[data-test=agent]").all_inner_texts()
        api_names = sorted(a["name"] for a in srv.get("/api/v1/agents")[1]["data"]["agents"])
        check("UI list shows exactly the agents of the API", sorted(c.split("\n")[0] for c in cards) == api_names, f"{cards[:2]}")
        page.fill("[data-test=create-name]", "uiagent")
        page.fill("[data-test=create-description]", "Created from the browser")
        page.click("[data-test=create-submit]")
        page.wait_for_url("**/admin/agents/uiagent")
        check("creating in the UI creates the agent via REST", srv.get("/api/v1/agents/uiagent")[0] == 200 and (agents / "uiagent" / "AGENT.md").is_file())
        page.wait_for_selector("[data-test=overview]")
        check("overview shows the real definition", "mock-echo" in page.inner_text("[data-test=overview]"))
        page.click("[data-test=tab-session]")
        page.fill("[data-test=message-input]", "hello from the browser")
        page.click("[data-test=send]")
        page.wait_for_function("document.querySelectorAll('[data-test=message]').length >= 2")
        check("session tab sends and shows the answer", "Mock response: hello from the browser" in page.inner_text(".content"))
        page.click("[data-test=tab-files]")
        page.wait_for_selector("[data-test=file]")
        page.click("[data-test=file]")
        page.wait_for_selector("[data-test=editor]")
        page.fill("[data-test=editor]", "this is not a valid agent file")
        page.click("[data-test=save]")
        page.wait_for_selector("[data-test=save-error]")
        check("editing AGENT.md to something invalid shows the server's reason", "must start with" in page.inner_text("[data-test=save-error]"))
        check("...and the agent on disk is unchanged", "Created from the browser" in (agents / "uiagent" / "AGENT.md").read_text(encoding="utf-8"))
        page.click("[data-test=tab-overview]")
        page.click("[data-test=archive]")
        check("archive asks for confirmation first", page.locator("[data-test=archive-confirm]").is_visible() and (agents / "uiagent").is_dir())
        page.click("[data-test=archive-yes]")
        page.wait_for_url("**/admin/agents")
        check("confirming archives the agent", eventually("archived", lambda: not (agents / "uiagent").exists()) and srv.get("/api/v1/agents/uiagent")[0] == 404)
        page.goto(srv.base + "/admin/scheduler")
        page.wait_for_selector("[data-test=task-row], [data-test=empty]")
        # The one 400 is the invalid AGENT.md this run saved on purpose; anything else is a defect.
        unexpected = [e for e in errors if "status of 400" not in e]
        check("no browser console or page errors besides the deliberate 400", not unexpected, str(unexpected[:3]))
        browser.close()
