#!/usr/bin/env python3
"""Installs the lesson-31 example agents from examples/agents into an agents directory.

    python scripts/c5_install_agents.py                       # into .oryxos/agents, as committed
    python scripts/c5_install_agents.py --only weather-daily  # one agent
    python scripts/c5_install_agents.py --provider mock --model mock-echo   # no API key needed

The files are copied unchanged except for the optional --provider / --model, which replace the
model line of every installed AGENT.md. Nothing is overwritten unless --force is given. Nothing is
sent anywhere: every example notifies a LOCAL receiver (http://127.0.0.1:18080/notify) until you
edit its notify_channels yourself.

The operator side still needs to allow what the agents ask for (the sandbox ceiling), for example:

    --oryxos.sandbox.hosts=api.open-meteo.com,hn.algolia.com,127.0.0.1
    --oryxos.sandbox.allow-private-networks=true
    --oryxos.sandbox.read-roots=.oryxos/workspace,.oryxos/agents
    --oryxos.sandbox.commands=python

See docs/coursework/week-05/demo-runbook.md.
"""
import argparse
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
EXAMPLES = REPO / "examples" / "agents"
MODEL_LINE = re.compile(r"^provider: \{name: [^,]+, model: [^,}]+,", re.MULTILINE)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", default=".oryxos/agents", help="agents directory (default .oryxos/agents)")
    ap.add_argument("--only", help="comma-separated agent names (default: all three)")
    ap.add_argument("--provider", help="replace the provider name, e.g. mock")
    ap.add_argument("--model", help="replace the model, required with --provider")
    ap.add_argument("--force", action="store_true", help="overwrite an existing agent directory")
    args = ap.parse_args()
    if bool(args.provider) != bool(args.model):
        ap.error("--provider and --model go together")
    available = sorted(p.name for p in EXAMPLES.iterdir() if (p / "AGENT.md").is_file())
    wanted = args.only.split(",") if args.only else available
    unknown = [name for name in wanted if name not in available]
    if unknown:
        ap.error("unknown agent(s) %s (available: %s)" % (unknown, available))
    target = Path(args.target)
    target.mkdir(parents=True, exist_ok=True)
    status = 0
    for name in wanted:
        destination = target / name
        if destination.exists() and not args.force:
            print("SKIP  %s already exists (use --force to replace it)" % destination)
            status = 1
            continue
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(EXAMPLES / name, destination, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        if args.provider:
            md = destination / "AGENT.md"
            text = md.read_text(encoding="utf-8")
            text, n = MODEL_LINE.subn("provider: {name: %s, model: %s," % (args.provider, args.model), text, count=1)
            if n != 1:
                print("FAIL  %s: no provider line to replace" % md)
                return 2
            md.write_text(text, encoding="utf-8")
        print("OK    installed %s" % destination)
    return status


if __name__ == "__main__":
    sys.exit(main())
