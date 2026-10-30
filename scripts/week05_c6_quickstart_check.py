#!/usr/bin/env python3
"""Quick Start check (lesson 32): the README steps, in an EMPTY workspace, against the real JAR.

    python scripts/week05_c6_quickstart_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar \
        --out docs/coursework/week-05/evidence/c6/quickstart-check.txt

What it does, in order (nothing outside a temporary directory is touched, no network is used):

  1. inspects the fat JAR: Spring Boot launcher, bundled libraries, the SQLite driver, and the
     admin console inside the web module's nested jar
  2. starts the JAR with NO profile directory, database or agents (the README's first run)
  3. health, info, Swagger UI, OpenAPI document, admin console, Prometheus
  4. installs the three example agents with scripts/c5_install_agents.py (one at a time, to prove
     each installs on its own) using the offline mock model, and waits for them to appear
  5. runs the weather agent by REST (same path as its 08:00 cron) and reads its outcome
  6. the unauthenticated API still refuses non-local requests (foreign Origin, forwarding header)
  7. restarts the JAR on the same workspace and checks that agents and history survived

The mock model only echoes, so this proves the wiring (install, register, schedule, run, audit,
restart) and NOT the tool calls or any real-model behaviour. Exit code 1 if any check failed.
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
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LINES = []
FAILED = []


def say(line):
    LINES.append(line)
    print(line, flush=True)


def check(name, ok, detail=""):
    say(f"  {'ok  ' if ok else 'FAIL'} {name}" + (f"  [{detail}]" if detail else ""))
    if not ok:
        FAILED.append(name)


def section(title):
    say(f"\n== {title}")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Jvm:
    def __init__(self, jar, work, port):
        self.base = f"http://127.0.0.1:{port}"
        self.log = work / f"server-{port}.log"
        args = [
            "java", "-jar", str(jar), f"--server.port={port}", "--logging.level.root=WARN",
            "--oryxos.sandbox.hosts=api.open-meteo.com,hn.algolia.com,127.0.0.1",
            "--oryxos.sandbox.allow-private-networks=true",
            "--oryxos.sandbox.read-roots=.oryxos/workspace,.oryxos/agents",
            "--oryxos.sandbox.commands=python",
        ]
        self.proc = subprocess.Popen(args, cwd=work, stdout=self.log.open("w"), stderr=subprocess.STDOUT)
        for _ in range(180):
            if self.proc.poll() is not None:
                raise RuntimeError("exited early:\n" + self.log.read_text(errors="replace")[-2000:])
            try:
                if self.get("/api/v1/health")[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.5)
        raise RuntimeError("never became healthy")

    def request(self, method, path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        h = {"Content-Type": "application/json"} if body is not None else {}
        h.update(headers or {})
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw, status = r.read(), r.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        text = raw.decode("utf-8", errors="replace")
        try:
            return status, json.loads(text)
        except ValueError:
            return status, text

    def get(self, path, headers=None):
        return self.request("GET", path, None, headers)

    def post(self, path, body=None, headers=None):
        return self.request("POST", path, body if body is not None else {}, headers)

    def stop(self):
        self.proc.terminate()
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()


def wait_for(fn, seconds=30):
    end = time.time() + seconds
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.5)
    return None


def agent_names(srv):
    status, body = srv.get("/api/v1/agents")
    if status != 200:
        return []
    data = body["data"]
    items = data if isinstance(data, list) else data.get("agents", [])
    return sorted(i["name"] for i in items)


def inspect_jar(jar):
    section("1. Fat JAR")
    check("jar exists and is large enough to hold its dependencies", jar.stat().st_size > 50_000_000,
          f"{jar.stat().st_size // 1_000_000} MB")
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        manifest = z.read("META-INF/MANIFEST.MF").decode()
        check("Spring Boot launcher is the main class", "org.springframework.boot.loader" in manifest)
        libs = [n for n in names if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
        check("dependencies are bundled", len(libs) > 100, f"{len(libs)} libraries")
        for needle in ("sqlite-jdbc", "spring-ai-openai", "picocli", "springdoc"):
            check(f"bundles {needle}", any(needle in n for n in libs))
        for module in ("core", "provider", "tool", "memory", "storage", "scheduler", "cli", "web"):
            check(f"bundles the oryxos-{module} module", any(f"oryxos-{module}-" in n for n in libs))
        web = next((n for n in libs if "oryxos-web-" in n), None)
        if web:
            with zipfile.ZipFile(__import__("io").BytesIO(z.read(web))) as w:
                wn = w.namelist()
                check("admin console index.html is inside the web module", "static/admin/index.html" in wn)
                check("admin console has bundled script and style assets",
                      any(n.startswith("static/admin/assets/") and n.endswith(".js") for n in wn)
                      and any(n.startswith("static/admin/assets/") and n.endswith(".css") for n in wn))
                check("admin console references no external URL",
                      b"https://" not in w.read("static/admin/index.html"))
        check("no secrets, databases or logs packaged",
              not any(n.endswith((".db", ".env", ".log")) or "MEMORY.md" in n for n in names))


def run(jar, out):
    work = Path(tempfile.mkdtemp(prefix="oryxos-quickstart-"))
    say(f"workspace (empty at start): {work.name}")
    srv = None
    try:
        inspect_jar(jar)

        section("2. First start in an empty workspace")
        port = free_port()
        srv = Jvm(jar, work, port)
        check("server healthy", srv.get("/api/v1/health")[0] == 200)
        check("database created in the workspace", (work / "oryxos-dev.db").exists())
        check("no agents yet", agent_names(srv) == [], str(agent_names(srv)))

        section("3. API, Swagger, Health, Admin, Prometheus")
        status, info = srv.get("/api/v1/info")
        check("info", status == 200)
        check("swagger ui", srv.get("/swagger-ui.html")[0] == 200)
        status, spec = srv.get("/v3/api-docs")
        check("openapi document lists the agent API", status == 200 and "/api/v1/agents" in json.dumps(spec))
        status, page = srv.get("/admin/")
        check("admin console served", status == 200 and "<div id=\"app\"" in str(page))
        status, page = srv.get("/admin/agents")
        check("admin deep link served (history mode)", status == 200)
        check("prometheus", srv.get("/actuator/prometheus")[0] == 200)
        check("profiles endpoint", srv.get("/api/v1/profiles")[0] == 200)
        check("tools endpoint lists shell_exec and notify",
              all(t in json.dumps(srv.get("/api/v1/tools")[1]) for t in ("shell_exec", "notify")))

        section("4. Install the three examples, one at a time")
        agents_dir = work / ".oryxos" / "agents"
        for index, name in enumerate(("weather-daily", "daily-tech-digest", "github-daily"), 1):
            result = subprocess.run(
                [sys.executable, str(REPO / "scripts" / "c5_install_agents.py"), "--target", str(agents_dir),
                 "--only", name, "--provider", "mock", "--model", "mock-echo"],
                capture_output=True, text=True, encoding="utf-8")
            check(f"installer exit 0 for {name}", result.returncode == 0, result.stderr.strip()[:120])
            got = wait_for(lambda: len(agent_names(srv)) == index and agent_names(srv))
            check(f"{name} registered without restart", bool(got) and name in got, str(got))
        check("only AGENT.md in weather-daily",
              sorted(p.name for p in (agents_dir / "weather-daily").rglob("*") if p.is_file()) == ["AGENT.md"])
        check("digest skill file installed", (agents_dir / "daily-tech-digest" / "skills" / "digest-format.md").exists())
        check("github script installed, no bytecode copied",
              (agents_dir / "github-daily" / "scripts" / "github_trending.py").exists()
              and not list(agents_dir.rglob("*.pyc")))
        installed = (agents_dir / "weather-daily" / "AGENT.md").read_text(encoding="utf-8")
        check("installed copy still says LOCAL DEMO ONLY next to the private-network line",
              "LOCAL DEMO ONLY" in installed)
        check("installed copy notifies only the local receiver", "http://127.0.0.1:18080/notify" in installed
              and "https://" not in installed.split("---")[1])
        status, schedules = srv.get("/api/v1/schedules")
        ids = json.dumps(schedules)
        check("three schedules registered (08:00, 09:00, 09:30 Asia/Shanghai)",
              all(i in ids for i in ("weather-daily.morning", "daily-tech-digest.morning", "github-daily.morning"))
              and "Asia/Shanghai" in ids)

        section("5. Run by REST (the cron's code path) and read the outcome")
        status, body = srv.post("/api/v1/schedules/weather-daily.morning/run")
        check("manual run accepted", status == 200, f"{status}")
        execs = wait_for(lambda: srv.get("/api/v1/schedules/weather-daily.morning/executions")[1]["data"])
        check("one execution recorded", bool(execs) and len(execs) == 1)
        if execs:
            execution = execs[0]
            check("trigger recorded as manual", "manual" in json.dumps(execution).lower())
            status, outcome = srv.get(f"/api/v1/schedules/weather-daily.morning/executions/{execution['id']}/outcome")
            check("outcome endpoint answers", status == 200 and outcome["data"]["outcome"] in
                  ("COMPLETED", "COMPLETED_WITH_TOOL_ISSUES", "FAILED"), str(outcome["data"]["outcome"]) if status == 200 else str(status))
            check("outcome matched by execution id", status == 200 and outcome["data"]["matchedBy"] == "execution_id")
        db = sqlite3.connect(work / "oryxos-dev.db")
        calls_before = db.execute("select count(*) from llm_calls").fetchone()[0]
        db.close()
        check("the run was audited in llm_calls", calls_before >= 1, f"{calls_before} rows")

        section("6. The unauthenticated API still refuses non-local requests")
        status, _ = srv.post("/api/v1/schedules/weather-daily.morning/run", headers={"Origin": "http://evil.example"})
        check("foreign Origin refused", status == 403, str(status))
        status, _ = srv.post("/api/v1/schedules/weather-daily.morning/run", headers={"X-Forwarded-For": "203.0.113.9"})
        check("forwarding header refused", status == 403, str(status))
        status, _ = srv.post("/api/v1/agents", {"name": "x-evil", "description": "d"},
                             headers={"Origin": "http://evil.example"})
        check("agent creation from a foreign origin refused", status == 403, str(status))
        check("server bound to loopback only", "address: 127.0.0.1" in
              (REPO / "oryxos-boot" / "src" / "main" / "resources" / "application.yaml").read_text(encoding="utf-8"))

        section("7. Restart on the same workspace")
        srv.stop()
        srv = Jvm(jar, work, free_port())
        check("agents survived the restart", agent_names(srv) == ["daily-tech-digest", "github-daily", "weather-daily"],
              str(agent_names(srv)))
        status, execs2 = srv.get("/api/v1/schedules/weather-daily.morning/executions")
        check("execution history survived", status == 200 and len(execs2["data"]) == 1)
        db = sqlite3.connect(work / "oryxos-dev.db")
        check("llm_calls rows survived", db.execute("select count(*) from llm_calls").fetchone()[0] == calls_before)
        version = db.execute("select max(version) from schema_migrations").fetchone()[0] if \
            db.execute("select count(*) from sqlite_master where name='schema_migrations'").fetchone()[0] else None
        db.close()
        check("schema migrated to the latest version (4)", version == 4, str(version))
    finally:
        if srv:
            srv.stop()
        say("")
        passed = sum(1 for line in LINES if line.startswith("  ok"))
        say(f"RESULT: {passed} passed, {len(FAILED)} failed")
        if out:
            Path(out).parent.mkdir(parents=True, exist_ok=True)
            text = "\n".join(LINES) + "\n"
            Path(out).write_text(text.replace(str(work), "<workspace>"), encoding="utf-8")
        shutil.rmtree(work, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out")
    args = ap.parse_args()
    run(Path(args.jar).resolve(), args.out)
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
