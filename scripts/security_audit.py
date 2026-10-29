#!/usr/bin/env python3
"""Repository security audit: scans tracked files AND the whole Git history for secrets and
personal or local data. Prints every hit with file and commit so a person can judge it.

    python scripts/security_audit.py            # exit 1 if anything needs review

What it looks for: API-key and token shapes (OpenAI/DeepSeek `sk-`, GitHub `gh*_`, AWS `AKIA`,
Google `AIza`, Slack `xox`), private keys, Bearer values, `key/token/secret/password = <value>`
assignments, webhook URLs with a token, URL user-info, absolute local paths, e-mail addresses,
and file names that should never be committed (databases, `.env`, logs, archives, build output,
real MEMORY.md, office/PDF course material).

Known harmless fixtures used by the tests (fake secrets that the tests prove are NOT leaked) are
listed in ALLOWED and reported separately, not as findings.
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

SECRET_PATTERNS = {
    "openai-style key": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}"),
    "github token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}"),
    "aws access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "google api key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "slack token": re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}"),
    "private key block": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "bearer value": re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{24,}"),
    "assigned secret": re.compile(
        r"(?i)\b(api[_-]?key|secret|token|passw(?:or)?d|webhook[_-]?(?:url|token))\b\s*[:=]\s*[\"']?(?!\$\{)(?!<)(?!\s)([A-Za-z0-9_./+=:-]{16,})"
    ),
    "webhook url": re.compile(r"https?://(hooks\.slack\.com|oapi\.dingtalk\.com|open\.feishu\.cn|open\.larksuite\.com|discord(?:app)?\.com/api/webhooks)/\S+"),
    "url with credentials": re.compile(r"https?://[^/\s:@]+:[^/\s@]+@[^/\s]+"),
}
PERSONAL_PATTERNS = {
    "e-mail address": re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    "absolute local path": re.compile(r"(?i)(?:[A-Z]:[\\/]+Users[\\/]+|/c/Users/|/home/[a-z0-9_-]+/|/Users/[A-Za-z0-9_-]+/)[^\s\"'`)<>|]*"),
}
FORBIDDEN_NAME = re.compile(
    r"(?i)\.(db|db-shm|db-wal|sqlite|sqlite3|bak|log|jar|class|pem|key|p12|jks|zip|7z|tar|gz|pdf|pptx?|docx?|xlsx?)$"
    r"|(^|/)\.env($|\.(?!example$))|(^|/)node_modules/|(^|/)target/|(^|/)MEMORY\.md$|static/admin/"
)

# Fake values that tests use to prove that secrets do NOT leak; reviewed by hand.
ALLOWED = [
    "SECRETKEY", "TOKEN-ABC123", "secret-token", "hunter2", "sk-secret", "SECRET-TOKEN",
    "TOP-SECRET", "SECRET", "token=", "api_key=SECRET", "<redacted>", "<HOME>",
    # attack strings in the URL/host parser tests and a documented trick in AllowListSandbox
    "evil.com", "evil.example", "api.example.com", "hooks.example.org", "user@x.org",
    "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", "sk-live-1234567890", "secret = Files.createDirectories",
]
# Placeholders in docs/templates (variable names without values) and the scanner itself.
SKIP_FILES = {"scripts/security_audit.py", "scripts/package_week04.py"}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=REPO, text=True, encoding="utf-8", errors="replace")


def scan_text(label, text, findings, harmless):
    for kind, pattern in {**SECRET_PATTERNS, **PERSONAL_PATTERNS}.items():
        for match in pattern.finditer(text):
            hit = match.group(0)
            line = text[max(0, text.rfind("\n", 0, match.start())) : text.find("\n", match.end())].strip()[:160]
            bucket = harmless if any(a in line for a in ALLOWED) else findings
            bucket.append((kind, label, hit[:80], line))


def main():
    findings, harmless = [], []

    # 1. tracked files, current tree
    for name in git("ls-files").splitlines():
        if name in SKIP_FILES:
            continue
        if FORBIDDEN_NAME.search(name):
            findings.append(("forbidden file name", name, name, ""))
            continue
        path = REPO / name
        if path.suffix.lower() in {".png", ".woff2", ".ico"} or not path.is_file():
            continue
        scan_text(name, path.read_text(encoding="utf-8", errors="ignore"), findings, harmless)

    # 2. every file name that was ever in history
    seen = set(git("log", "--all", "--name-only", "--format=").split())
    for name in sorted(seen):
        if FORBIDDEN_NAME.search(name) and not (REPO / name).exists():
            findings.append(("forbidden file name in history", name, name, ""))

    # 3. content of every commit patch
    commits = git("rev-list", "--all").split()
    for sha in commits:
        patch = git("show", "--format=", "--no-color", "-U0", sha)
        added = "\n".join(l[1:] for l in patch.splitlines() if l.startswith("+") and not l.startswith("+++"))
        scan_text(f"history:{sha[:7]}", added, findings, harmless)

    # 4. commit identities (personal data that becomes public with the history)
    identities = sorted(set(git("log", "--all", "--format=%an <%ae>%n%cn <%ce>").split("\n")) - {""})

    # de-duplicate
    def uniq(items):
        out, seen_keys = [], set()
        for item in items:
            key = (item[0], item[2], item[1].split(":")[0] if item[1].startswith("history:") else item[1])
            if key not in seen_keys:
                seen_keys.add(key)
                out.append(item)
        return out

    findings, harmless = uniq(findings), uniq(harmless)
    print(f"scanned {len(git('ls-files').splitlines())} tracked files and {len(commits)} commits")
    print(f"commit identities that will be public: {identities}")
    print(f"\nharmless test fixtures (fake secrets the tests prove are not leaked): {len(harmless)}")
    for kind, label, hit, line in harmless[:10]:
        print(f"  ok   [{kind}] {label}: {hit}")
    print(f"\nFINDINGS needing review: {len(findings)}")
    for kind, label, hit, line in findings:
        print(f"  HIT  [{kind}] {label}: {hit}\n         {line}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
