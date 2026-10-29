#!/usr/bin/env python3
"""Summarise surefire/failsafe XML reports (run after `mvn verify`) as a Markdown table.

    python scripts/test_summary.py > docs/coursework/week04/evidence/test-summary.md
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODULES = ["core", "provider", "tool", "memory", "storage", "scheduler", "cli", "web", "boot"]


def attr(header, key):
    return int(re.search(key + r'="(\d+)"', header).group(1))


def main():
    per_module = {}
    per_class = []
    for module in MODULES:
        for kind in ("surefire-reports", "failsafe-reports"):
            for xml in sorted((REPO / f"oryxos-{module}" / "target" / kind).glob("TEST-*.xml")):
                header = re.search(r"<testsuite [^>]*", xml.read_text(encoding="utf-8")[:4000]).group(0)
                name = re.search(r' name="([^"]+)"', header).group(1).rsplit(".", 1)[-1]
                tests, skipped = attr(header, "tests"), attr(header, "skipped")
                failed = attr(header, "failures") + attr(header, "errors")
                per_class.append((module, name, tests, skipped, failed, "failsafe" if kind.startswith("fail") else "surefire"))
                total = per_module.setdefault(module, [0, 0, 0])
                total[0] += tests
                total[1] += skipped
                total[2] += failed
    print("## Per module\n")
    print("| Module | Tests | Skipped | Failed |\n|---|---|---|---|")
    for module in MODULES:
        t = per_module.get(module, [0, 0, 0])
        print(f"| oryxos-{module} | {t[0]} | {t[1]} | {t[2]} |")
    totals = [sum(v[i] for v in per_module.values()) for i in range(3)]
    print(f"| **Total** | **{totals[0]}** | **{totals[1]}** | **{totals[2]}** |\n")
    print("## Per test class\n")
    print("| Module | Class | Tests | Skipped | Failed | Runner |\n|---|---|---|---|---|---|")
    for row in per_class:
        print("| oryxos-{} | {} | {} | {} | {} | {} |".format(*row))
    return 0 if totals[2] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
