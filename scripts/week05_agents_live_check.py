#!/usr/bin/env python3
"""Live check of the week-5 plugin agents (lessons 29 and 30) against the REAL executable JAR.

Starts the JAR in separate JVMs (loopback only, a temporary workspace and SQLite file, the Mock
model, no network, no API key), drives it over HTTP and the CLI, reads SQLite and the files
directly, and restarts the JVM to compare state. Nothing here replaces `mvn clean verify`.

    python scripts/week05_agents_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar \
        --stage c3 --out docs/coursework/week-05/evidence/live-check-c3.txt

Reuses the helpers of week04_live_check.py. Exit code 1 if any check failed.
"""
import argparse
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from week04_live_check import RESULTS, TRANSCRIPT, Server, check, free_port, rows, say, section

DAILY = """---
name: daily-reconcile
description: Reconciles the day's payments
provider: {{name: mock, model: m, temperature: 0.0}}
tools: [file_read]
settings:
  max_iterations: 5
  sandbox:
    read_roots: ["{allowed}"]
schedules:
  - {{id: nightly, cron: "0 0 2 1 1 *", zone: UTC, message: "Reconcile yesterday"}}
---
# Daily reconcile

Compare the ledger with the bank file. INSTRUCTIONS-MARKER
"""


def build_workspace(work):
    allowed = work / "ws" / "allowed"
    forbidden = work / "ws" / "forbidden"
    allowed.mkdir(parents=True)
    forbidden.mkdir(parents=True)
    (allowed / "ledger.txt").write_text("LEDGER-CONTENT")
    (forbidden / "secret.txt").write_text("FORBIDDEN-CONTENT")
    profiles = work / "profiles"
    profiles.mkdir()
    (profiles / "legacy.yaml").write_text(
        "name: legacy\ndescription: old style\nprovider: {name: mock, model: m, temperature: 0.0}\n")
    (profiles / "twin.yaml").write_text(
        "name: twin\ndescription: LEGACY-TWIN\nprovider: {name: mock, model: m, temperature: 0.0}\n")
    agents = work / "agents"
    daily = agents / "daily-reconcile"
    (daily / "skills").mkdir(parents=True)
    (daily / "scripts").mkdir()
    (daily / "AGENT.md").write_text(DAILY.format(allowed=allowed.as_posix()), encoding="utf-8")
    (daily / "REFERENCE.md").write_text("REFERENCE-CONTENT-NOT-IN-PROMPT")
    (daily / "skills" / "report-format.md").write_text("SKILL-CONTENT")
    (daily / "scripts" / "reconcile.py").write_text("open('SCRIPT-RAN.txt','w').write('x')\n")
    (agents / "broken").mkdir()
    (agents / "broken" / "AGENT.md").write_text("no frontmatter here")
    (agents / "twin").mkdir()
    (agents / "twin" / "AGENT.md").write_text(
        "---\ndescription: FROM-AGENT-MD\nprovider: {name: mock, model: m}\n---\nbody\n")
    return profiles, agents, allowed, forbidden


def cli(jar, work, args, env_extra):
    env = dict(os.environ)
    env.update(env_extra)
    done = subprocess.run(["java", "-jar", str(jar), *args], cwd=work, env=env,
                          capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    return done.returncode, done.stdout


def stage_c3(jar, base):
    work = base / "c3"
    work.mkdir()
    profiles, agents, allowed, forbidden = build_workspace(work)
    db = work / "live.db"
    extra = [f"--oryxos.agents-directory={agents.as_posix()}"]
    srv = Server(jar, work, free_port(), profiles, db, extra)
    try:
        section("Lesson 29: AGENT.md directory -> running agent (no Java change, no repackaging)")
        names = {p["name"]: p for p in srv.get("/api/v1/profiles")[1]["data"]}
        check("REST /profiles lists the AGENT.md agent", "daily-reconcile" in names, str(sorted(names)))
        check("REST /profiles lists the legacy profile", "legacy" in names)
        check("the invalid directory is not registered", "broken" not in names)
        check("name clash: the legacy profile stays active",
              names.get("twin", {}).get("description") == "LEGACY-TWIN", str(names.get("twin")))
        check("declared schedule is registered with the scheduler",
              any(t["taskId"] == "daily-reconcile.nightly" and t["cron"] == "0 0 2 1 1 *"
                  for t in srv.get("/api/v1/schedules")[1]["data"]))
        check("scheduled_tasks row exists",
              rows(db, "select task_id, profile_name, enabled from scheduled_tasks") ==
              [("daily-reconcile.nightly", "daily-reconcile", 1)])
        status, reply = srv.invoke("daily-reconcile", "hello plugin", "p1")
        check("agent runs with the Mock provider", status == 200 and reply == "Mock response: hello plugin", reply)
        check("one audited model call", rows(db, "select count(*) from llm_calls where session_id='api:p1:daily-reconcile'")[0][0] == 1)
        read_ok = srv.invoke("daily-reconcile", f'tool:file_read {{"path":"{(allowed / "ledger.txt").as_posix()}"}}', "p2")[1]
        check("declared sandbox lets it read its directory", "LEDGER-CONTENT" in read_ok, read_ok)
        denied = srv.invoke("daily-reconcile", f'tool:file_read {{"path":"{(forbidden / "secret.txt").as_posix()}"}}', "p2")[1]
        check("anything else is denied", "SANDBOX_DENIED" in denied and "FORBIDDEN-CONTENT" not in denied, denied)
        check("denial is audited as SandboxDenied",
              rows(db, "select tool_name, success, error_category from tool_invocations "
                       "where session_id='api:p2:daily-reconcile' order by id") ==
              [("file_read", 1, None), ("file_read", 0, "SandboxDenied")])
        check("no script was executed by loading or running the agent",
              not (work / "SCRIPT-RAN.txt").exists() and not (agents / "daily-reconcile" / "SCRIPT-RAN.txt").exists())
        for path, label in [("/api/v1/health", "Health"), ("/swagger-ui/index.html", "Swagger"), ("/admin/", "Admin")]:
            check(f"{label} answers 200", srv.get(path)[0] == 200)

        section("Lesson 29: the CLI sees the same agents")
        env = {"ORYXOS_PROFILE_DIR": profiles.as_posix(), "ORYXOS_AGENTS_DIR": agents.as_posix(),
               "ORYXOS_JDBC_URL": f"jdbc:sqlite:{(work / 'cli.db').as_posix()}"}
        code, out = cli(jar, work, ["profile", "list"], env)
        table = " | ".join(line for line in out.splitlines() if "[main]" not in line)
        check("`profile list` shows the AGENT.md agent and the legacy one",
              code == 0 and "daily-reconcile" in table and "legacy" in table and "broken" not in table, table.strip()[:300])
        check("the invalid directory is reported (logged), not silently skipped", "broken" in out and "must start with" in out)

        before = {
            "tasks": rows(db, "select * from scheduled_tasks order by task_id"),
            "llm": rows(db, "select * from llm_calls order by id"),
            "tool": rows(db, "select * from tool_invocations order by id"),
        }
    finally:
        srv.stop()

    section("Lesson 29: restart keeps the agent, its schedule and the audit")
    srv2 = Server(jar, work, free_port(), profiles, db, extra)
    try:
        names = {p["name"] for p in srv2.get("/api/v1/profiles")[1]["data"]}
        check("agent registered again after restart", "daily-reconcile" in names)
        check("scheduled_tasks identical across restart",
              rows(db, "select * from scheduled_tasks order by task_id")[0][:6] == before["tasks"][0][:6])
        check("llm_calls identical across restart", rows(db, "select * from llm_calls order by id") == before["llm"])
        check("tool_invocations identical across restart", rows(db, "select * from tool_invocations order by id") == before["tool"])
        check("agent still answers", srv2.invoke("daily-reconcile", "after restart", "p3")[1] == "Mock response: after restart")
    finally:
        srv2.stop()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--stage", default="c3", choices=["c3", "c4", "all"])
    ap.add_argument("--out")
    args = ap.parse_args()
    jar = Path(args.jar).resolve()
    base = Path(tempfile.mkdtemp(prefix="oryxos-week05-agents-"))
    say(f"JAR: {jar.name}, scratch dir: {base}")
    started = time.time()
    try:
        if args.stage in ("c3", "all"):
            stage_c3(jar, base)
        if args.stage in ("c4", "all"):
            import week05_c4_live_check  # noqa: F401  (added with lesson 30)
            week05_c4_live_check.stage_c4(jar, base)
    finally:
        passed = sum(RESULTS)
        say(f"\n{passed}/{len(RESULTS)} checks passed in {time.time() - started:.0f}s")
        if args.out:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text("\n".join(TRANSCRIPT) + "\n", encoding="utf-8")
    sys.exit(0 if all(RESULTS) else 1)


if __name__ == "__main__":
    main()
