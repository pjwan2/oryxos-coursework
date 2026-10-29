#!/usr/bin/env python3
"""Assemble the week-4 submission material and zip it, refusing to include anything sensitive.

    python scripts/package_week04.py

Reads (all tracked in Git):  docs/coursework/week-04/*  and the two hand-written files already in
deliverables/week-04/ (README.md, source-reference.md).
Writes:
    deliverables/week-04/...                         the submission folder (copies refreshed from docs/)
    deliverables/oryxos-week-04-coursework.zip        ONLY the submission folder
    deliverables/oryxos-week-04-source.zip                 `git archive` of HEAD: tracked files only, so no
                                                     databases, logs, .env, node_modules or build output
Nothing is uploaded or pushed. Exit code 1 if the safety scan finds anything.
"""
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WEEK = REPO / "docs" / "coursework" / "week-04"
OUT = REPO / "deliverables" / "week-04"

FORBIDDEN_NAMES = re.compile(r"(^|/)(\.env[^/]*|[^/]+\.(db|db-shm|db-wal|sqlite|bak|log|jar|class|pem|key)|node_modules|target|\.git)(/|$)")
ALLOWED_NAMES = re.compile(r"(^|/)\.env\.example$")
SECRET_PATTERNS = [
    re.compile(r"sk-[A-Za-z0-9]{20,}"),
    re.compile(r"(?i)api[_-]?key\s*[:=]\s*[\"']?[A-Za-z0-9]{20,}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]{30,}"),
]


def refresh_copies():
    required = ["acceptance-report.md", "requirements-matrix.md", "known-limitations.md", "architecture.md"]
    for name in required:
        if not (WEEK / name).is_file():
            sys.exit(f"missing {WEEK / name}: write the report first")
    (OUT / "design").mkdir(parents=True, exist_ok=True)
    for name in ("requirements-matrix.md", "acceptance-report.md", "known-limitations.md", "architecture.md", "acceptance-report-baseline-f31fd9f.md"):
        shutil.copy2(WEEK / name, OUT / name)
    for name in ("memory-design.md", "sandbox-design.md", "scheduler-design.md", "web-service-design.md"):
        shutil.copy2(WEEK / name, OUT / "design" / name)
    evidence_src = WEEK / "evidence"
    if evidence_src.is_dir():
        shutil.copytree(evidence_src, OUT / "evidence", dirs_exist_ok=True)
    for name in ("README.md", "source-reference.md"):
        if not (OUT / name).is_file():
            sys.exit(f"missing hand-written {OUT / name}")


def scan(label, entries):
    """entries: iterable of (name, bytes-or-None). Returns a list of problems."""
    problems = []
    for name, data in entries:
        if FORBIDDEN_NAMES.search(name) and not ALLOWED_NAMES.search(name):
            problems.append(f"{label}: forbidden file {name}")
            continue
        if data is not None and len(data) < 2_000_000 and not name.endswith((".png", ".zip", ".woff2")):
            text = data.decode("utf-8", errors="ignore")
            for pattern in SECRET_PATTERNS:
                if pattern.search(text):
                    problems.append(f"{label}: {name} matches {pattern.pattern}")
    return problems


def make_zip(path, root, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(files):
            z.write(f, f.relative_to(root).as_posix())


def main():
    refresh_copies()
    files = [p for p in OUT.rglob("*") if p.is_file()]
    problems = scan("materials", ((p.relative_to(OUT).as_posix(), p.read_bytes()) for p in files))

    commit = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=REPO, text=True).strip()
    materials = REPO / "deliverables" / "oryxos-week-04-coursework.zip"
    source = REPO / "deliverables" / "oryxos-week-04-source.zip"
    for pattern in ("OryxOS-*.zip", "oryxos-source-*.zip", "oryxos-week-04-*.zip"):  # stale archives
        for old in REPO.joinpath("deliverables").glob(pattern):
            old.unlink()
    subprocess.check_call(["git", "archive", "--format=zip", "--prefix=oryxos-coursework/", f"--output={source}", "HEAD"], cwd=REPO)

    with zipfile.ZipFile(source) as z:
        problems += scan("source", ((i.filename, z.read(i) if not i.is_dir() else None) for i in z.infolist()))
    if problems:
        for p in problems:
            print("FAIL", p)
        source.unlink(missing_ok=True)
        sys.exit(1)

    make_zip(materials, OUT, files)
    with zipfile.ZipFile(materials) as z:
        names = z.namelist()
    print(f"materials: {materials.relative_to(REPO)}  ({len(names)} files, {materials.stat().st_size // 1024} KiB)")
    with zipfile.ZipFile(source) as z:
        count = len(z.namelist())
    print(f"source   : {source.relative_to(REPO)}  ({count} files, {source.stat().st_size // 1024} KiB, git HEAD {commit})")
    print("safety scan: no databases, logs, .env files, build output, keys or credential patterns found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
