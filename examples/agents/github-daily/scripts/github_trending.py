#!/usr/bin/env python3
"""Repository data for the github-daily agent (OryxOS lesson 31, demo 3).

Prints ONE JSON object on stdout and nothing else:

    {"generated_for": "2026-03-05", "source": "...", "windows": {...},
     "today": [...], "month": [...], "ai": [...], "meta": {...}}

Every repository is {"name", "stars", "description", "url"}.

What this is NOT: GitHub has no public "Trending" API. This script uses the Search Repositories
API and approximates a trending list as "repositories CREATED inside a time window, ordered by
total stars". It does not measure star growth and is not GitHub's Trending page. The window
definitions are returned in the output so a report can say exactly what was measured.

  today  repositories created since the previous UTC date (a 24-48 hour window, so the list is not
         empty early in the day)
  month  repositories created in the last 30 days
  ai     repositories created in the last 30 days that carry ANY of several AI topics. Topics are
         queried one by one and merged, because a single query with several `topic:` terms means
         "all of them" (AND) and would return almost nothing.

Safety properties (this process is NOT sandboxed by OryxOS: it can reach the network by itself,
which the host allow-list of the http_get tool does not cover):
  * only the standard library; no shell, no eval, no file access, no environment variables read
  * every request has a timeout; every response is capped at MAX_RESPONSE_BYTES
  * an explicit User-Agent; HTTP 403/429 rate limits are reported, never retried in a loop
  * on any failure: a JSON error object on stdout and a non-zero exit code
      2 network error or timeout    3 rate limited    4 bad or oversized response
      5 bad arguments
  * text taken from GitHub (descriptions) is shortened and stripped of control characters, because
    a model will read it and it must be treated as data, never as instructions
  * no credential is read, sent or printed
"""
import argparse
import datetime
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_API_BASE = "https://api.github.com"
USER_AGENT = "oryxos-github-daily/1.0 (+https://github.com/pjwan2/oryxos-coursework)"
TIMEOUT_SECONDS = 10
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_PAGES = 3
MAX_LIMIT = 10
MAX_DESCRIPTION = 200
AI_TOPICS = ["ai", "llm", "machine-learning", "deep-learning", "artificial-intelligence"]
SOURCE = (
    "GitHub Search Repositories API: repositories created inside a time window, ordered by stars. "
    "An approximation; NOT the official GitHub Trending list."
)

EXIT_NETWORK, EXIT_RATE_LIMIT, EXIT_BAD_RESPONSE, EXIT_BAD_ARGS = 2, 3, 4, 5
CONTROL = re.compile(r"[\x00-\x1f\x7f]+")


class Failure(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise Failure(EXIT_BAD_ARGS, "bad arguments: " + message)


def parse_args(argv):
    parser = Parser(description="GitHub repository digest data", add_help=False)
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--today", default=None, help="YYYY-MM-DD (UTC); default: today")
    args = parser.parse_args(argv)
    if not 1 <= args.limit <= MAX_LIMIT:
        raise Failure(EXIT_BAD_ARGS, "--limit must be between 1 and %d" % MAX_LIMIT)
    base = args.api_base.rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise Failure(EXIT_BAD_ARGS, "--api-base must be an http(s) URL without credentials")
    if args.today is None:
        today = datetime.datetime.now(datetime.timezone.utc).date()
    else:
        try:
            today = datetime.date.fromisoformat(args.today)
        except ValueError:
            raise Failure(EXIT_BAD_ARGS, "--today must be YYYY-MM-DD")
    return args.limit, base, today


def get_json(url):
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        remaining = error.headers.get("X-RateLimit-Remaining")
        if error.code == 429 or (error.code == 403 and (remaining == "0" or error.headers.get("Retry-After"))):
            reset = error.headers.get("X-RateLimit-Reset") or error.headers.get("Retry-After") or "unknown"
            raise Failure(EXIT_RATE_LIMIT, "GitHub rate limit reached (reset/retry-after: %s)" % reset)
        raise Failure(EXIT_BAD_RESPONSE, "GitHub answered HTTP %d" % error.code)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise Failure(EXIT_NETWORK, "network error: %s" % type(error).__name__)
    if len(raw) > MAX_RESPONSE_BYTES:
        raise Failure(EXIT_BAD_RESPONSE, "response larger than %d bytes" % MAX_RESPONSE_BYTES)
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise Failure(EXIT_BAD_RESPONSE, "response is not valid JSON")


def clean(text):
    text = CONTROL.sub(" ", text or "").strip()
    return text if len(text) <= MAX_DESCRIPTION else text[: MAX_DESCRIPTION - 1] + "…"


def repository(item):
    name = item.get("full_name")
    url = item.get("html_url")
    stars = item.get("stargazers_count")
    if not isinstance(name, str) or not isinstance(url, str) or not isinstance(stars, int):
        return None
    return {"name": clean(name), "stars": stars, "description": clean(item.get("description")), "url": url}


def search(base, query, wanted, counter):
    """Up to MAX_PAGES pages of one query, unique by repository id, stars descending."""
    per_page = min(max(wanted * 2, 10), 100)
    found, seen = [], set()
    for page in range(1, MAX_PAGES + 1):
        params = urllib.parse.urlencode(
            {"q": query, "sort": "stars", "order": "desc", "per_page": per_page, "page": page}
        )
        document = get_json("%s/search/repositories?%s" % (base, params))
        counter[0] += 1
        items = document.get("items") if isinstance(document, dict) else None
        if not isinstance(items, list):
            raise Failure(EXIT_BAD_RESPONSE, "response has no items list")
        for item in items:
            if not isinstance(item, dict):
                continue
            key = item.get("id", item.get("full_name"))
            entry = repository(item)
            if entry is None or key in seen:
                continue
            seen.add(key)
            found.append((key, entry))
        if len(found) >= wanted or len(items) < per_page:
            break
    return found


def top(found, limit):
    return [entry for _, entry in sorted(found, key=lambda pair: (-pair[1]["stars"], pair[1]["name"]))][:limit]


def build(limit, base, today):
    since_today = today - datetime.timedelta(days=1)
    since_month = today - datetime.timedelta(days=30)
    counter = [0]
    today_list = top(search(base, "created:>=%s" % since_today.isoformat(), limit, counter), limit)
    month_list = top(search(base, "created:>=%s" % since_month.isoformat(), limit, counter), limit)
    merged = {}
    for topic in AI_TOPICS:
        query = "topic:%s created:>=%s" % (topic, since_month.isoformat())
        for key, entry in search(base, query, limit, counter):
            merged.setdefault(key, entry)
    ai_list = top(list(merged.items()), limit)
    return {
        "generated_for": today.isoformat(),
        "source": SOURCE,
        "windows": {
            "today": "created on or after %s (UTC)" % since_today.isoformat(),
            "month": "created on or after %s (UTC)" % since_month.isoformat(),
            "ai": "created on or after %s (UTC), any of the topics %s" % (since_month.isoformat(), ", ".join(AI_TOPICS)),
        },
        "today": today_list,
        "month": month_list,
        "ai": ai_list,
        "meta": {"limit": limit, "requests": counter[0], "empty": not (today_list or month_list or ai_list)},
    }


def main(argv):
    try:
        limit, base, today = parse_args(argv)
        result = build(limit, base, today)
    except Failure as failure:
        sys.stdout.write(json.dumps({"error": failure.message, "code": failure.code}, ensure_ascii=False) + "\n")
        return failure.code
    sys.stdout.write(json.dumps(result, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
