#!/usr/bin/env python3
"""Check that relative Markdown links in tracked documents point at files that exist.

    python scripts/check_doc_links.py            # exit 1 if a link is broken

Only `[text](relative/path)` links are checked (anchors are stripped; http(s) and mailto are
skipped). Files under docs/coursework/*/evidence are raw captured output and are not scanned.
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")


def main():
    tracked = subprocess.check_output(["git", "ls-files", "*.md"], cwd=REPO, text=True).split()
    broken = []
    for name in tracked:
        if "/evidence/" in name or name.startswith("deliverables/"):
            continue
        path = REPO / name
        text = path.read_text(encoding="utf-8")
        in_fence = False
        for number, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
            if in_fence:
                continue
            for target in LINK.findall(line):
                if re.match(r"^(https?:|mailto:|#)", target):
                    continue
                target = target.split("#", 1)[0]
                if target and not (path.parent / target).exists():
                    broken.append(f"{name}:{number}: {target}")
    for item in broken:
        print("BROKEN", item)
    print(f"{len(tracked)} markdown files checked, {len(broken)} broken links")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
