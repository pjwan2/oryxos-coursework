#!/usr/bin/env python3
"""Tier A live check of the three lesson-31 demo agents against the REAL executable JAR.

The agents are the committed files in examples/agents, installed unchanged (apart from the model
line, the fixture addresses and the cron) with scripts/c5_install_agents.py into a temporary working
directory's .oryxos/agents. The JAR runs as a separate JVM with cwd = that directory, loopback only.
Only the outside world is replaced, by local servers in this file:

  * a model that speaks the OpenAI chat protocol and is scripted per agent (it reads the same
    instructions, memory and tool results a real model would read)
  * the weather API, the news API and the GitHub Search API (the real Python script runs against it)
  * the chat group's webhook receiver

    python scripts/week05_c5_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar \
        --out docs/coursework/week-05/evidence/c5/live-check-c5.txt

This proves OUR pipeline end to end. It does not prove that a real LLM follows the instructions,
and nothing here touches Open-Meteo, Hacker News, GitHub or a real chat group (tier B:
scripts/week05_c5_external_check.py). Exit code 1 if any check failed.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from zoneinfo import ZoneInfo

from week04_live_check import RESULTS, TRANSCRIPT, Server, check, free_port, rows, say, section

REPO = Path(__file__).resolve().parent.parent
WEATHER, TECH, GITHUB = "weather-daily", "daily-tech-digest", "github-daily"
TASKS = {WEATHER: "weather-daily.morning", TECH: "daily-tech-digest.morning", GITHUB: "github-daily.morning"}


# ---------------------------------------------------------------- the outside world (fixtures)


class Fixtures:
    """All local servers and their request logs."""

    def __init__(self):
        self.weather_status = 200
        self.weather_hits = []
        self.news_hits = []
        self.news = [
            ("Show HN: A tiny Rust web server", "https://example.org/rust", 420, "1001"),
            ("SQLite 4 design notes", "https://example.org/sqlite", 380, "1002"),
            ("New AI chip doubles inference speed", "https://example.org/aichip", 300, "1003"),
            ("Why I switched my editor", "https://example.org/editor", 260, "1004"),
            ("Open-source LLM beats benchmark", "https://example.org/llm", 240, "1005"),
            ("Linux 7.0 released", "https://example.org/linux", 200, "1006"),
            ("GPU shortage easing in 2026", "https://example.org/gpu", 180, "1007"),
            ("A history of the spreadsheet", "https://example.org/sheet", 150, "1008"),
            ("Ask HN: favorite CLI tools?", "", 120, "1009"),
        ]
        self.github_queries = []
        self.posts = []
        self.requests = []  # model requests since the last clear
        self.model_calls = 0  # every model request, never cleared
        self.servers = []

    def serve(self, handler_factory):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler_factory)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.servers.append(server)
        return "http://127.0.0.1:%d" % server.server_port

    def stop(self):
        for server in self.servers:
            server.shutdown()


def handler(fixtures, routes):
    class H(BaseHTTPRequestHandler):
        def _body(self):
            if self.headers.get("Content-Length"):
                return self.rfile.read(int(self.headers["Content-Length"]))
            data = b""
            while True:  # chunked (the JDK client)
                size = int(self.rfile.readline().strip().split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    return data
                data += self.rfile.read(size)
                self.rfile.readline()

        def _go(self):
            url = urlsplit(self.path)
            body = self._body() if self.command == "POST" else b""
            status, payload = routes(self.command, url.path, parse_qs(url.query), body, url.query)
            raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = _go

        def log_message(self, *args):
            pass

    return H


GITHUB_REPOS = {
    "today": [(1, "acme/fresh-db", 910, "A brand new embedded database"), (2, "acme/terminal-kit", 640, "Terminal UI helpers"), (3, "zed/zine", 300, "Static site generator")],
    "month": [(4, "acme/big-month", 4800, "Month leader"), (1, "acme/fresh-db", 910, "A brand new embedded database"), (5, "neo/compiler-lab", 800, "Learn compilers")],
    "ai": {
        "ai": [(10, "lab/ai-agents", 3900, "Autonomous AI agents toolkit"), (11, "lab/llm-kit", 2100, "LLM application kit")],
        "llm": [(11, "lab/llm-kit", 2100, "LLM application kit"), (12, "lab/rag-engine", 1800, "RAG engine")],
        "machine-learning": [(13, "lab/ml-toolbox", 1500, "Classic ML toolbox")],
        "deep-learning": [],
        "artificial-intelligence": [(10, "lab/ai-agents", 3900, "Autonomous AI agents toolkit")],
    },
}


def start_fixtures(fx):
    def weather(method, path, query, body, raw):
        fx.weather_hits.append(raw)
        if fx.weather_status != 200:
            return fx.weather_status, {}
        return 200, {"current": {"time": "2026-03-05T08:00", "temperature_2m": 6.5, "precipitation": 1.2, "wind_speed_10m": 34.0, "weather_code": 61}}

    def news(method, path, query, body, raw):
        fx.news_hits.append(raw)
        return 200, {"hits": [{"title": t, "url": (u or None), "points": p, "objectID": i} for t, u, p, i in fx.news]}

    def github(method, path, query, body, raw):
        q = query["q"][0]
        fx.github_queries.append(q)
        topic = re.search(r"topic:([a-z-]+)", q)
        if topic:
            pool = GITHUB_REPOS["ai"].get(topic.group(1), [])
        else:
            pool = GITHUB_REPOS["today"] if "created:>=" + fx.today_start in q else GITHUB_REPOS["month"]
        pool = sorted(pool, key=lambda r: -r[2])
        per, page = int(query["per_page"][0]), int(query["page"][0])
        part = pool[(page - 1) * per: page * per]
        return 200, {"total_count": len(pool), "items": [
            {"id": i, "full_name": n, "stargazers_count": s, "description": d, "html_url": "https://github.com/" + n} for i, n, s, d in part]}

    def receiver(method, path, query, body, raw):
        data = json.loads(body)
        fx.posts.append((data.get("title"), data.get("text")))
        return 200, {}

    def model(method, path, query, body, raw):
        request = json.loads(body)
        fx.requests.append(request)
        fx.model_calls += 1
        decision = brain(request)
        message = {"role": "assistant", "content": decision.get("text")}
        finish = "stop"
        if decision.get("tool"):
            message = {"role": "assistant", "content": None, "tool_calls": [
                {"id": "call_" + decision["tool"], "type": "function", "function": {"name": decision["tool"], "arguments": decision["args"]}}]}
            finish = "tool_calls"
        return 200, {"id": "c", "object": "chat.completion", "created": 0, "model": "fake-model",
                     "choices": [{"index": 0, "finish_reason": finish, "message": message}],
                     "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}}

    fx.today_start = "2026-03-04"
    return {
        "weather": fx.serve(handler(fx, weather)),
        "news": fx.serve(handler(fx, news)),
        "github": fx.serve(handler(fx, github)),
        "receiver": fx.serve(handler(fx, receiver)),
        "model": fx.serve(handler(fx, model)),
    }


# ---------------------------------------------------------------- the scripted model


def system_of(request):
    return next((m.get("content") or "" for m in request["messages"] if m["role"] == "system"), "")


def last_tool_name(request):
    for m in reversed(request["messages"]):
        if m.get("tool_calls"):
            return m["tool_calls"][0]["function"]["name"]
    return ""


def call(tool, **args):
    return {"tool": tool, "args": json.dumps(args, ensure_ascii=False)}


def notify(title, message):
    return call("notify", target="local-receiver", title=title, message=message)


INTEREST = re.compile(r"(?i)\b(ai|llm|gpu|chip|chips)\b|芯片")


def brain(request):
    system = system_of(request)
    last = request["messages"][-1]
    if last["role"] == "user" and (last.get("content") or "").startswith("记住"):
        return call("save_memory", content=re.sub(r"^记住[：:]\s*", "", last["content"]), scope="CORE")
    if last["role"] == "tool" and last_tool_name(request) == "save_memory":
        return {"text": "已记住。"}
    tool, result = last_tool_name(request), last.get("content") or ""
    failed = result.startswith("ERROR")
    memory = system[system.find("## Long-term memory"):] if "## Long-term memory" in system else ""

    if "每日天气与穿搭助手" in system:
        if last["role"] != "tool":
            return call("http_get", url=re.search(r"Forecast URL: (\S+)", system).group(1))
        if tool == "http_get":
            if failed:
                return notify("今日天气与穿搭", "天气获取失败：" + re.sub(r"^ERROR\s*", "", result))
            try:
                cur = json.loads(result)["current"]
            except (ValueError, KeyError):
                return notify("今日天气与穿搭", "天气获取失败：返回内容里没有 current 字段")
            t, p, w = cur["temperature_2m"], cur["precipitation"], cur["wind_speed_10m"]
            advice = "北京：%s℃，降水 %s mm，风速 %s km/h。" % (t, p, w)
            advice += "很冷，穿羽绒服。" if t < 5 else "偏凉，穿外套和长裤。" if t < 15 else "温暖，穿薄外套。"
            advice += "有降水，记得带伞。" if p > 0 else ""
            advice += "风较大，注意防风。" if w > 30 else ""
            return notify("今日天气与穿搭", advice)
        return {"text": "发送失败：" + result if failed else "已发送"}

    if "每日科技日报编辑" in system:
        if last["role"] != "tool":
            return call("file_read", path=re.search(r"`([^`]*digest-format\.md)`", system).group(1))
        if tool == "file_read":
            return call("http_get", url=re.search(r"News URL: (\S+)", system).group(1))
        if tool == "http_get":
            if failed:
                return notify("科技日报", "今天没有可用的科技新闻：" + re.sub(r"^ERROR\s*", "", result))
            hits = json.loads(result).get("hits", [])
            if not hits:
                return notify("科技日报", "今天没有可用的科技新闻：返回的 hits 为空")
            hits.sort(key=lambda s: -s.get("points", 0))
            if "AI" in memory or "芯片" in memory:
                hits = [s for s in hits if INTEREST.search(s["title"])] + [s for s in hits if not INTEREST.search(s["title"])]
            text = "科技日报 · 2026-03-05\n"
            for n, s in enumerate(hits[:8], 1):
                url = s.get("url") or "https://news.ycombinator.com/item?id=" + s["objectID"]
                text += "\n%d. %s\n   点评：HN %d 分\n   来源：%s\n" % (n, s["title"], s["points"], url)
            return notify("科技日报", text)
        return {"text": "发送失败：" + result if failed else "已发送"}

    if "每日 GitHub 仓库日报编辑" in system:
        if last["role"] != "tool":
            m = re.search(r'\{"command":"[a-z0-9]+","args":\[[^\]]*\]\}', system)
            data = json.loads(m.group())
            return {"tool": "shell_exec", "args": json.dumps(data)}
        if tool == "shell_exec":
            if failed or '"error"' in result:
                return notify("GitHub 日报", "GitHub 日报失败：" + re.sub(r"^ERROR\s*", "", result))
            data = json.loads(result)
            if data["meta"]["empty"]:
                return notify("GitHub 日报", "今天没有查到数据")
            keyword = next((k for k in ("rag", "agent", "llm") if k in memory.lower()), None)

            def section_text(title, items, boost=None):
                if boost:
                    items = sorted(items, key=lambda i: boost not in (i["name"] + i["description"]).lower())
                return "\n%s\n" % title + "".join("%d. %s ★%d — %s %s\n" % (n, i["name"], i["stars"], i["description"], i["url"]) for n, i in enumerate(items, 1))

            text = "GitHub 日报\n" + section_text("今日热门 Top 5", data["today"]) + section_text("本月热门 Top 5", data["month"]) + section_text("AI 项目重点", data["ai"], keyword)
            return notify("GitHub 日报", text + "\n口径：数据来自 GitHub Search API（按创建日期窗口 + star 排序），不是官方 Trending。")
        return {"text": "发送失败：" + result if failed else "已发送"}
    return {"text": "ok"}


# ---------------------------------------------------------------- helpers


def install(work, urls):
    """Installs the committed examples with scripts/c5_install_agents.py, then edits only fixtures."""
    agents = work / ".oryxos" / "agents"
    subprocess.run([sys.executable, str(REPO / "scripts" / "c5_install_agents.py"), "--target", str(agents),
                    "--provider", "fake-openai", "--model", "fake-model"], check=True, capture_output=True)
    receiver = urls["receiver"] + "/notify"
    for name in (WEATHER, TECH, GITHUB):
        md = agents / name / "AGENT.md"
        text = md.read_text(encoding="utf-8").replace("http://127.0.0.1:18080/notify", receiver)
        if name == WEATHER:
            text = text.replace("https://api.open-meteo.com/v1/forecast", urls["weather"] + "/v1/forecast")
        if name == TECH:
            text = text.replace("https://hn.algolia.com/api/v1/search", urls["news"] + "/api/v1/search")
        if name == GITHUB:
            text = text.replace("'https://api\\.github\\.com'", "'http://127\\.0\\.0\\.1:[0-9]{2,5}'\n          - '--today'\n          - '2026-03-05'")
            text = text.replace("max_args: 5", "max_args: 7")
            text = text.replace('"--limit","5"]', '"--limit","5","--api-base","%s","--today","2026-03-05"]' % urls["github"])
        md.write_text(text, encoding="utf-8")
    return agents


def tool_rows(db, execution_id):
    return [f"{t}:{'ok' if s else 'fail'}:{c}" for t, s, c in rows(
        db, f"select tool_name, success, error_category from tool_invocations where execution_id='{execution_id}' order by id")]


def eventually(what, condition, seconds=40):
    end = time.time() + seconds
    while time.time() < end:
        if condition():
            return True
        time.sleep(0.3)
    say("      (timed out waiting for: %s)" % what)
    return False


def snapshot(db):
    return {t: rows(db, f"select * from {t} order by {o}") for t, o in [
        ("sessions", "session_id"), ("llm_calls", "id"), ("tool_invocations", "id"),
        ("scheduled_tasks", "task_id"), ("task_executions", "id")]}


def cron_in(zone, seconds):
    at = datetime.now(ZoneInfo(zone)) + timedelta(seconds=seconds)
    return "%d %d %d * * *" % (at.second, at.minute, at.hour)


def cli(jar, work, db, urls, args):
    env = dict(os.environ)
    env.update({
        "SPRING_DATASOURCE_URL": "jdbc:sqlite:" + db.as_posix(),
        "ORYXOS_PROVIDERS_0_NAME": "mock", "ORYXOS_PROVIDERS_0_TYPE": "mock", "ORYXOS_PROVIDERS_0_MODEL": "mock-echo",
        "ORYXOS_PROVIDERS_1_NAME": "fake-openai", "ORYXOS_PROVIDERS_1_TYPE": "openai-compatible",
        "ORYXOS_PROVIDERS_1_MODEL": "fake-model", "ORYXOS_PROVIDERS_1_BASEURL": urls["model"], "ORYXOS_PROVIDERS_1_APIKEY": "dummy-key",
        "ORYXOS_SANDBOX_HOSTS": "127.0.0.1", "ORYXOS_SANDBOX_ALLOWPRIVATENETWORKS": "true",
        "ORYXOS_SANDBOX_READROOTS": ".oryxos/workspace,.oryxos/agents", "ORYXOS_SANDBOX_COMMANDS": "python",
        "ORYXOS_MEMORY_MARKDOWNPATH": ".oryxos/memory/MEMORY.md",
    })
    done = subprocess.run(["java", "-jar", str(jar), *args], cwd=work, env=env, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=240)
    return done.returncode, done.stdout


# ---------------------------------------------------------------- the check


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out")
    args = ap.parse_args()
    jar = Path(args.jar).resolve()
    base = Path(tempfile.mkdtemp(prefix="oryxos-c5-live-"))
    work = base / "w"
    (work / ".oryxos" / "profiles").mkdir(parents=True)
    (work / ".oryxos" / "workspace").mkdir(parents=True)
    db = work / "live.db"
    fx = Fixtures()
    urls = start_fixtures(fx)
    agents = install(work, urls)
    say("JAR: %s, scratch dir: %s" % (jar.name, base))
    started = time.time()
    extra = [
        "--oryxos.providers[0].name=mock", "--oryxos.providers[0].type=mock", "--oryxos.providers[0].model=mock-echo",
        "--oryxos.providers[1].name=fake-openai", "--oryxos.providers[1].type=openai-compatible",
        "--oryxos.providers[1].model=fake-model", "--oryxos.providers[1].base-url=" + urls["model"],
        "--oryxos.providers[1].api-key=dummy-key-not-a-secret",
        "--oryxos.sandbox.hosts=127.0.0.1", "--oryxos.sandbox.allow-private-networks=true",
        "--oryxos.sandbox.read-roots=.oryxos/workspace,.oryxos/agents", "--oryxos.sandbox.commands=python",
        "--oryxos.agents-watch-debounce-millis=300",
    ]
    profiles = work / ".oryxos" / "profiles"
    srv = Server(jar, work, free_port(), profiles, db, extra)
    try:
        run_checks(srv, jar, work, db, fx, urls, agents)
        before = snapshot(db)
    finally:
        srv.stop()

    section("Restart: configuration, memory, schedule and audit survive")
    files = {p.relative_to(agents).as_posix(): p.read_bytes() for p in agents.rglob("*") if p.is_file() and "__pycache__" not in p.parts}
    srv2 = Server(jar, work, free_port(), profiles, db, extra)
    try:
        names = {a["name"] for a in srv2.get("/api/v1/agents")[1]["data"]["agents"]}
        check("after restart: the three agents are registered again", {WEATHER, TECH, GITHUB} <= names, str(sorted(names)))
        check("after restart: all five tables are identical", snapshot(db) == before)
        check("after restart: agent files (AGENT.md, MEMORY.md, skills, scripts) are byte-identical",
              {p.relative_to(agents).as_posix(): p.read_bytes() for p in agents.rglob("*") if p.is_file() and "__pycache__" not in p.parts} == files)
        tasks = {t["taskId"]: t for t in srv2.get("/api/v1/schedules")[1]["data"]}
        check("after restart: schedules, zones and run counts are back",
              all(tasks[t]["zone"] == "Asia/Shanghai" and tasks[t]["runCount"] >= 1 for t in TASKS.values()))
        fx.posts.clear()
        run = srv2.post("/api/v1/schedules/%s/run" % TASKS[GITHUB])
        check("after restart: an agent still runs end to end", run[0] == 200 and run[1]["data"]["success"] and len(fx.posts) == 1)
    finally:
        srv2.stop()
        fx.stop()

    passed = sum(RESULTS)
    say("\n%d/%d checks passed in %.0fs" % (passed, len(RESULTS), time.time() - started))
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text("\n".join(TRANSCRIPT).replace(str(base), "<TMP>") + "\n", encoding="utf-8")
    shutil.rmtree(base, ignore_errors=True)
    sys.exit(0 if all(RESULTS) else 1)


def run_checks(srv, jar, work, db, fx, urls, agents):
    section("The three committed examples are installed and registered")
    status, body, _ = srv.get("/api/v1/agents")
    listed = {a["name"]: a for a in body["data"]["agents"]}
    check("REST lists weather-daily, daily-tech-digest and github-daily", {WEATHER, TECH, GITHUB} <= set(listed), str(sorted(listed)))
    check("no directory was reported as invalid", body["data"]["problems"] == {}, str(body["data"]["problems"]))
    check("weather-daily has only AGENT.md in its directory", sorted(p.name for p in (agents / WEATHER).iterdir()) == ["AGENT.md"])
    check("tech digest directory = AGENT.md + skills/digest-format.md",
          sorted(p.relative_to(agents / TECH).as_posix() for p in (agents / TECH).rglob("*") if p.is_file()) == ["AGENT.md", "skills/digest-format.md"])
    check("github directory = AGENT.md + scripts/github_trending.py",
          sorted(p.relative_to(agents / GITHUB).as_posix() for p in (agents / GITHUB).rglob("*") if p.is_file() and "__pycache__" not in p.parts) == ["AGENT.md", "scripts/github_trending.py"])
    check("tools: weather = http_get + notify", sorted(listed[WEATHER]["tools"]) == ["http_get", "notify"])
    check("tools: github has shell_exec and no http_get", "shell_exec" in listed[GITHUB]["tools"] and "http_get" not in listed[GITHUB]["tools"])
    tasks = {t["taskId"]: t for t in srv.get("/api/v1/schedules")[1]["data"]}
    check("committed schedules: 08:00, 09:00, 09:30, Asia/Shanghai",
          [(tasks[TASKS[a]]["cron"], tasks[TASKS[a]]["zone"]) for a in (WEATHER, TECH, GITHUB)] ==
          [("0 0 8 * * *", "Asia/Shanghai"), ("0 0 9 * * *", "Asia/Shanghai"), ("0 30 9 * * *", "Asia/Shanghai")])
    code, out = cli(jar, work, db, urls, ["profile", "list"])
    check("the CLI lists the same three agents", code == 0 and all(n in out for n in (WEATHER, TECH, GITHUB)), out.strip()[:160])
    for path, label in [("/api/v1/health", "Health"), ("/swagger-ui/index.html", "Swagger"), ("/admin/", "Admin")]:
        check("%s answers 200" % label, srv.get(path)[0] == 200)

    section("Manual runs over REST: weather, tech digest, github (the real Python script)")
    fx.requests.clear(); fx.posts.clear()
    w = srv.post("/api/v1/schedules/%s/run" % TASKS[WEATHER])[1]["data"]
    check("weather: 3 model requests, 1 weather request, 1 notification", len(fx.requests) == 3 and len(fx.weather_hits) == 1 and len(fx.posts) == 1)
    check("weather: tool rows = http_get ok, notify ok (by execution id)", tool_rows(db, w["executionId"]) == ["http_get:ok:None", "notify:ok:None"])
    check("weather: advice built from the fetched data", all(x in fx.posts[0][1] for x in ("6.5℃", "带伞", "防风")), fx.posts[0][1])
    check("weather: the model saw a prompt without frontmatter or webhook address",
          "notify_channels" not in system_of(fx.requests[0]) and urls["receiver"] not in system_of(fx.requests[0]))
    fx.requests.clear(); fx.posts.clear()
    t = srv.post("/api/v1/schedules/%s/run" % TASKS[TECH])[1]["data"]
    check("tech: tool rows = file_read, http_get, notify", tool_rows(db, t["executionId"]) == ["file_read:ok:None", "http_get:ok:None", "notify:ok:None"])
    check("tech: skill text is NOT in the first prompt but arrives as a file_read result",
          "DIGEST-FORMAT-MARKER" not in system_of(fx.requests[0]) and "DIGEST-FORMAT-MARKER" in json.dumps(fx.requests[1], ensure_ascii=False))
    order_before = [l.split(". ", 1)[1] for l in fx.posts[0][1].splitlines() if re.match(r"^\d+\. ", l)]
    check("tech: 8 items, score order without a preference", len(order_before) == 8 and order_before[0] == "Show HN: A tiny Rust web server")
    fx.requests.clear(); fx.posts.clear(); fx.github_queries.clear()
    g = srv.post("/api/v1/schedules/%s/run" % TASKS[GITHUB])[1]["data"]
    check("github: tool rows = shell_exec ok, notify ok", tool_rows(db, g["executionId"]) == ["shell_exec:ok:None", "notify:ok:None"])
    check("github: the script itself called the GitHub API (created-date window, stars desc)",
          any("created:>=2026-03-04" in q for q in fx.github_queries) and len(fx.github_queries) >= 7, str(fx.github_queries[:2]))
    check("github: script JSON reached the model, script source did not",
          "acme/fresh-db" in json.dumps(fx.requests[1], ensure_ascii=False) and "MAX_RESPONSE_BYTES" not in json.dumps(fx.requests, ensure_ascii=False))
    report = fx.posts[0][1]
    check("github: three sections and the honest ranking note",
          all(x in report for x in ("今日热门 Top 5", "本月热门 Top 5", "AI 项目重点", "不是官方 Trending")))
    for run, task in ((w, WEATHER), (t, TECH), (g, GITHUB)):
        o = srv.get("/api/v1/schedules/%s/executions/%d/outcome" % (TASKS[task], run["id"]))[1]["data"]
        check("%s: outcome COMPLETED, matched by execution id" % task, o["outcome"] == "COMPLETED" and o["matchedBy"] == "execution_id")

    section("Memory preference changes the report, per agent")
    check("tech: remember a preference through the API", "已记住" in srv.invoke(TECH, "记住：优先关注AI和芯片", "owner")[1])
    srv.invoke(GITHUB, "记住：优先关注 RAG 相关项目", "owner")
    mem_t = (agents / TECH / "MEMORY.md").read_text(encoding="utf-8")
    mem_g = (agents / GITHUB / "MEMORY.md").read_text(encoding="utf-8")
    check("each preference is in its own agent's MEMORY.md only", "AI和芯片" in mem_t and "RAG" not in mem_t and "RAG" in mem_g and "AI和芯片" not in mem_g)
    check("the weather agent has no memory file", not (agents / WEATHER / "MEMORY.md").exists())
    fx.requests.clear(); fx.posts.clear()
    srv.post("/api/v1/schedules/%s/run" % TASKS[TECH])
    order_after = [l.split(". ", 1)[1] for l in fx.posts[0][1].splitlines() if re.match(r"^\d+\. ", l)]
    check("tech: the same news is now ordered AI/chip first", order_after[:3] == ["New AI chip doubles inference speed", "Open-source LLM beats benchmark", "GPU shortage easing in 2026"], str(order_after[:4]))
    check("tech: the preference text was in the model's prompt", "AI和芯片" in system_of(fx.requests[0]))
    fx.requests.clear(); fx.posts.clear()
    srv.post("/api/v1/schedules/%s/run" % TASKS[GITHUB])
    ai = fx.posts[0][1][fx.posts[0][1].index("AI 项目重点"):]
    check("github: the AI highlights now put RAG first", ai.index("lab/rag-engine") < ai.index("lab/ai-agents"))
    check("github prompt never carries the tech preference", all("AI和芯片" not in system_of(r) for r in fx.requests))

    section("Failures are not business successes")
    fx.posts.clear(); fx.weather_status = 500
    f = srv.post("/api/v1/schedules/%s/run" % TASKS[WEATHER])[1]["data"]
    o = srv.get("/api/v1/schedules/%s/executions/%d/outcome" % (TASKS[WEATHER], f["id"]))[1]["data"]
    fx.weather_status = 200
    check("weather outage: technically finished, outcome COMPLETED_WITH_TOOL_ISSUES", f["success"] and o["outcome"] == "COMPLETED_WITH_TOOL_ISSUES")
    check("weather outage: the message says it failed and invents no weather", fx.posts[0][1].startswith("天气获取失败") and "穿" not in fx.posts[0][1])
    check("weather outage: audit shows http_get fail, notify ok", tool_rows(db, f["executionId"]) == ["http_get:fail:ToolReturnedFailure", "notify:ok:None"])
    md = (agents / GITHUB / "AGENT.md").read_text(encoding="utf-8")
    bad = md.replace('"command":"python","args":[', '"command":"python","args":["-c","print(1)",')
    s, b, _ = srv.request("PUT", "/api/v1/agents/" + GITHUB, {"agentMd": bad})
    fx.posts.clear(); fx.github_queries.clear()
    r = srv.post("/api/v1/schedules/%s/run" % TASKS[GITHUB])[1]["data"]
    check("github: any other argument is refused by the sandbox and no process starts",
          s == 200 and tool_rows(db, r["executionId"])[0] == "shell_exec:fail:SandboxDenied" and not fx.github_queries, str(tool_rows(db, r["executionId"])))
    srv.request("PUT", "/api/v1/agents/" + GITHUB, {"agentMd": md})

    section("The cron trigger of the running scheduler fires all three agents (no human call)")
    before = {t: rows(db, f"select count(*) from task_executions where task_id='{t}' and trigger_type='cron'")[0][0] for t in TASKS.values()}
    fx.posts.clear()
    committed = {a: (agents / a / "AGENT.md").read_text(encoding="utf-8") for a in TASKS}
    for agent, secs in ((WEATHER, 6), (TECH, 8), (GITHUB, 10)):
        text = committed[agent]
        text = re.sub(r'cron: "[^"]+"', 'cron: "%s"' % cron_in("Asia/Shanghai", secs), text, count=1)
        srv.request("PUT", "/api/v1/agents/" + agent, {"agentMd": text})
    done = eventually("three cron runs", lambda: all(rows(db, f"select count(*) from task_executions where task_id='{t}' and trigger_type='cron'")[0][0] > before[t] for t in TASKS.values()))
    check("each agent got exactly one new cron run", done and all(rows(db, f"select count(*) from task_executions where task_id='{t}' and trigger_type='cron'")[0][0] == before[t] + 1 for t in TASKS.values()))
    eventually("three notifications", lambda: len(fx.posts) >= 3, 15)
    check("the three reports reached the receiver", sorted(p[0] for p in fx.posts) == ["GitHub 日报", "今日天气与穿搭", "科技日报"], str([p[0] for p in fx.posts]))
    for agent, task in TASKS.items():
        eid = rows(db, f"select execution_id from task_executions where task_id='{task}' and trigger_type='cron' order by id desc limit 1")[0][0]
        want = {WEATHER: ["http_get:ok:None", "notify:ok:None"], TECH: ["file_read:ok:None", "http_get:ok:None", "notify:ok:None"], GITHUB: ["shell_exec:ok:None", "notify:ok:None"]}[agent]
        check("cron run of %s: tool rows by execution id" % agent, tool_rows(db, eid) == want, str(tool_rows(db, eid)))

    for agent in TASKS:  # put the committed schedules back
        srv.request("PUT", "/api/v1/agents/" + agent, {"agentMd": committed[agent]})
    tasks = {t["taskId"]: t for t in srv.get("/api/v1/schedules")[1]["data"]}
    check("the committed 08:00 / 09:00 / 09:30 schedules are back after the test", [tasks[TASKS[a]]["cron"] for a in (WEATHER, TECH, GITHUB)] == ["0 0 8 * * *", "0 0 9 * * *", "0 30 9 * * *"])

    section("CLI path: the same agent, the same tools")
    fx.posts.clear()
    code, out = cli(jar, work, db, urls, ["chat", WEATHER, "现在是早上，请查询今天的天气并发送穿搭建议。"])
    cli_rows = [f"{t}:{'ok' if s else 'fail'}:{c}" for t, s, c in rows(db, "select tool_name, success, error_category from tool_invocations where session_id='cli:local:weather-daily' order by id")]
    check("CLI chat ran the weather agent (reply 已发送, one notification)", code == 0 and "已发送" in out and len(fx.posts) == 1, out.strip()[-120:])
    check("CLI audit rows equal the REST and cron rows", cli_rows == ["http_get:ok:None", "notify:ok:None"], str(cli_rows))

    section("Totals: every table agrees with what the servers saw")
    check("no failed model call anywhere", rows(db, "select count(*) from llm_calls where success=0")[0][0] == 0)
    check("every scheduled tool call carries an execution id", rows(db, "select count(*) from tool_invocations where session_id like 'scheduler:%' and execution_id is null")[0][0] == 0)
    check("llm_calls rows = model requests the stub model received (every call is audited, none invented)",
          rows(db, "select count(*) from llm_calls")[0][0] == fx.model_calls, "%s vs %d" % (rows(db, "select count(*) from llm_calls")[0][0], fx.model_calls))
    ui_checks(srv, agents, fx)


def ui_checks(srv, agents, fx):
    section("Admin console, in a real headless browser (Playwright)")
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
        names = [c.split("\n")[0] for c in page.locator("[data-test=agent]").all_inner_texts()]
        check("Agents list shows the three demo agents", {WEATHER, TECH, GITHUB} <= set(names), str(names))
        page.goto(srv.base + "/admin/agents/" + WEATHER)
        page.wait_for_selector("[data-test=overview]")
        overview = page.inner_text("[data-test=overview]")
        check("overview shows tools, the 08:00 schedule and its zone", "http_get" in overview and "0 0 8" in overview and "Asia/Shanghai" in overview)
        fx.posts.clear()
        page.click("[data-test=run]")
        page.click("[data-test=run-yes]")
        page.wait_for_selector("[data-test=last-run]", timeout=30000)
        check("manual run (after the confirm step) shows technical status and tool audit apart",
              "Technical: Finished without error" in page.inner_text("[data-test=last-run]") and "Tools: 2 tool call(s) succeeded" in page.inner_text("[data-test=last-run]"))
        check("the run reached the receiver", len(fx.posts) == 1)
        if not page.locator("[data-test=call]").count():  # a run just started here opens its own details
            page.locator("[data-test=details]").first.click()
        page.wait_for_selector("[data-test=call]")
        calls = page.locator("[data-test=call]").all_inner_texts()
        check("tool calls of the run are listed (http_get, notify)", any("http_get" in c for c in calls) and any("notify" in c for c in calls), str(calls))
        page.click("[data-test=tab-files]")
        page.wait_for_selector("[data-test=file]")
        page.click("[data-test=file]")
        page.wait_for_selector("[data-test=editor]")
        check("Files tab shows the real AGENT.md", "每日天气与穿搭助手" in page.input_value("[data-test=editor]"))
        page.goto(srv.base + "/admin/agents/" + TECH)
        page.wait_for_selector("[data-test=tab-session]")
        page.click("[data-test=tab-session]")
        page.fill("[data-test=message-input]", "记住：UI-PREFERENCE-CHECK")
        page.click("[data-test=send]")
        page.wait_for_function("document.body.innerText.includes('已记住')")
        page.click("[data-test=tab-memory]")
        page.wait_for_selector("[data-test=core-entry]")
        check("Session tab sent the message; Memory tab shows it as this agent's private memory",
              "UI-PREFERENCE-CHECK" in page.inner_text("main") and "private to this agent" in page.inner_text("main"))
        page.click("[data-test=tab-files]")
        page.wait_for_selector("[data-test=file]")
        files = page.locator("[data-test=file]").all_inner_texts()
        check("tech digest Files tab lists the skill file", "digest-format.md" in " ".join(files), str(files))
        page.goto(srv.base + "/admin/agents/" + GITHUB)
        page.wait_for_selector("[data-test=tab-files]")
        page.click("[data-test=tab-files]")
        page.wait_for_selector("[data-test=file]")
        check("github Files tab lists the script", "github_trending.py" in " ".join(page.locator("[data-test=file]").all_inner_texts()))
        page.goto(srv.base + "/admin/scheduler")
        page.wait_for_selector("[data-test=task-row]")
        check("Scheduler page lists the three tasks", page.locator("[data-test=task-row]").count() >= 3)
        check("no browser console or page errors", not errors, str(errors[:3]))
        browser.close()


if __name__ == "__main__":
    main()
