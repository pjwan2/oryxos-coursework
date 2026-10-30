#!/usr/bin/env python3
"""Tier B of the lesson-31 demos: what happens against the REAL outside world.

Each item is attempted once, read-only, and recorded as VERIFIED (with the evidence) or NOT
VERIFIED (with the reason). Nothing is invented: a failure is written down as a failure.

    python scripts/week05_c5_external_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar \
        --out docs/coursework/week-05/evidence/c5

Items:
  1. Open-Meteo, plain HTTP GET with the URL from weather-daily/AGENT.md
  2. weather-daily on the real JAR with its REAL http_get tool reaching api.open-meteo.com (the model
     and the chat group are still local stand-ins)
  3. Hacker News Search (Algolia), plain HTTP GET with the URL from daily-tech-digest/AGENT.md
  4. scripts/github_trending.py against the real GitHub Search API (unauthenticated: 10 requests per
     minute; this script needs 7 of them)
  5. a real LLM provider: only if DEEPSEEK_API_KEY is set in the environment (the value is never
     read, printed or stored)
  6. a real webhook: never attempted without an explicitly authorised target (WEBHOOK_URL is not a
     setting of this project; nothing is sent)

No key is ever requested, printed or committed.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import week05_c5_live_check as live
from week04_live_check import Server, free_port, rows

REPO = Path(__file__).resolve().parent.parent
LINES = []


def log(line):
    print(line, flush=True)
    LINES.append(line)


def agent_url(agent, label):
    text = (REPO / "examples" / "agents" / agent / "AGENT.md").read_text(encoding="utf-8")
    return re.search(label + r": (\S+)", text).group(1)


def get(url, limit=200000):
    request = urllib.request.Request(url, headers={"User-Agent": "oryxos-c5-external-check/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read(limit), dict(response.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(2000), dict(e.headers)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return None, ("%s: %s" % (type(e).__name__, e)).encode(), {}


def verdict(name, ok, evidence):
    log("%s  %s  -> %s" % ("VERIFIED    " if ok else "NOT VERIFIED", name, evidence))
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    log("Tier B (real external services) -- run at %s" % time.strftime("%Y-%m-%d %H:%M:%S %z"))

    # 1. Open-Meteo, direct
    url = agent_url("weather-daily", "Forecast URL")
    status, body, headers = get(url)
    ok = status == 200 and b'"current"' in body
    if ok:
        (out / "external-open-meteo.json").write_bytes(body)
        current = json.loads(body)["current"]
        verdict("1. Open-Meteo direct GET", True, "HTTP 200, current=%s (saved: external-open-meteo.json)" % json.dumps(current))
    else:
        verdict("1. Open-Meteo direct GET", False, "status=%s body=%s" % (status, body[:160]))

    # 2. weather-daily through the real JAR and the real http_get tool
    if ok:
        run_weather_on_jar(Path(args.jar).resolve(), out)
    else:
        verdict("2. weather-daily through the JAR's real http_get", False, "skipped: item 1 failed, so the host is not reachable from here")

    # 3. Hacker News (Algolia)
    status, body, _ = get(agent_url("daily-tech-digest", "News URL"))
    try:
        hits = json.loads(body).get("hits", []) if status == 200 else []
    except ValueError:
        hits = []
    if hits:
        (out / "external-hn.json").write_text(json.dumps(hits[:5], ensure_ascii=False, indent=1), encoding="utf-8")
        verdict("3. Hacker News Search (Algolia)", True, "HTTP 200, %d hits, first: %r (first 5 saved: external-hn.json)" % (len(hits), hits[0].get("title")))
    else:
        verdict("3. Hacker News Search (Algolia)", False, "status=%s body=%s" % (status, body[:160]))

    # 4. GitHub Search API through the real script
    script = REPO / "examples" / "agents" / "github-daily" / "scripts" / "github_trending.py"
    done = subprocess.run([sys.executable, str(script), "--limit", "3"], capture_output=True, text=True, encoding="utf-8", timeout=120)
    try:
        data = json.loads(done.stdout)
    except ValueError:
        data = {"error": "stdout is not JSON: %r" % done.stdout[:120]}
    if done.returncode == 0 and "today" in data:
        (out / "external-github.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        verdict("4. GitHub Search API via github_trending.py", True,
                "exit 0, today=%d month=%d ai=%d, top month: %s (saved: external-github.json)" % (
                    len(data["today"]), len(data["month"]), len(data["ai"]), (data["month"] or [{}])[0].get("name")))
    else:
        verdict("4. GitHub Search API via github_trending.py", False, "exit %s: %s" % (done.returncode, data.get("error", done.stderr[:160])))

    # 5. real LLM
    verdict("5. Real LLM provider (DeepSeek)", False,
            "no DEEPSEEK_API_KEY in the environment; nothing was called" if not os.environ.get("DEEPSEEK_API_KEY")
            else "a key is present but this script deliberately makes no paid model call; run the agents with it manually (runbook)")
    # 6. real webhook
    verdict("6. Real webhook (a chat group)", False, "not attempted: no explicitly authorised target; all examples notify only http://127.0.0.1:18080/notify")

    (out / "external-tier-b.txt").write_text("\n".join(LINES) + "\n", encoding="utf-8")


def run_weather_on_jar(jar, out):
    base = Path(tempfile.mkdtemp(prefix="oryxos-c5-ext-"))
    work = base / "w"
    (work / ".oryxos" / "profiles").mkdir(parents=True)
    fx = live.Fixtures()
    urls = live.start_fixtures(fx)
    live.install(work, urls)
    md = work / ".oryxos" / "agents" / "weather-daily" / "AGENT.md"
    text = md.read_text(encoding="utf-8").replace(urls["weather"] + "/v1/forecast", "https://api.open-meteo.com/v1/forecast")
    md.write_text(text, encoding="utf-8")
    db = work / "ext.db"
    extra = [
        "--oryxos.providers[0].name=mock", "--oryxos.providers[0].type=mock", "--oryxos.providers[0].model=mock-echo",
        "--oryxos.providers[1].name=fake-openai", "--oryxos.providers[1].type=openai-compatible",
        "--oryxos.providers[1].model=fake-model", "--oryxos.providers[1].base-url=" + urls["model"],
        "--oryxos.providers[1].api-key=dummy-key-not-a-secret",
        "--oryxos.sandbox.hosts=127.0.0.1,api.open-meteo.com", "--oryxos.sandbox.allow-private-networks=true",
    ]
    srv = Server(jar, work, free_port(), work / ".oryxos" / "profiles", db, extra)
    try:
        s, b, _ = srv.post("/api/v1/schedules/weather-daily.morning/run")
        execution = b["data"]
        tools = rows(db, "select tool_name, success, error_category from tool_invocations where execution_id='%s' order by id" % execution["executionId"])
        message = fx.posts[0][1] if fx.posts else ""
        good = s == 200 and tools == [("http_get", 1, None), ("notify", 1, None)] and fx.weather_hits == [] and re.search(r"北京：-?[\d.]+℃", message)
        evidence = "tool rows=%s; message: %s" % (tools, message[:140].replace("\n", " "))
        if good:
            (out / "external-open-meteo-via-agent.txt").write_text(
                "weather-daily on the real JAR, real http_get -> api.open-meteo.com (model and chat group are local stand-ins)\n"
                "tool_invocations: %s\nnotification received: %s\n" % (tools, message), encoding="utf-8")
        verdict("2. weather-daily through the JAR's real http_get", bool(good), evidence)
    finally:
        srv.stop()
        fx.stop()


if __name__ == "__main__":
    main()
