# Week-5 evidence index (C1 to C6)

Real output of commands run on the author's machine (Windows 10, JDK 21.0.2, Maven 3.9.16). Local paths were replaced by `<HOME>` and terminal colour codes removed.

| File | What it is |
|---|---|
| `mvn-clean-verify.txt` | `mvn -B -ntp clean verify` on the C2 code: nine modules SUCCESS, 538 tests, 4 skipped, 0 failed, static analysis clean |
| `mvn-clean-verify-after-c1.txt` | the same after C1 only (517 tests) |
| `test-summary.md` | tests, skipped and failed per module and per class (`scripts/test_summary.py`) |
| `live-check.txt` | `scripts/week05_live_check.py` on the real JAR: 31 checks (weather and memory with the real audit rows, isolation, scheduler and the `outcome` endpoint, JVM restart with a table-by-table comparison) |
| `week04-live-check-regression.txt` | week-4's 77 live checks re-run on the same JAR: 77/77 |
| `dashboard-headless-run.txt` | headless-browser run of the six dashboard pages on the same JAR: 27/27 (automated, not a human review) |
| `mvn-clean-verify-c3.txt` | `mvn -B -ntp clean verify` on the C3 code (lesson 29): nine modules SUCCESS, 616 tests, 5 skipped, 0 failed |
| `live-check-c3.txt` | `scripts/week05_agents_live_check.py --stage c3` on the real JAR: 22 checks (AGENT.md agent registered at startup, REST + CLI, name clash, schedule, sandbox, restart) |
| `mvn-clean-verify-c4.txt` | `mvn -B -ntp clean verify` on the C4 code (lesson 30): nine modules SUCCESS, 728 tests, 6 skipped, 0 failed, static analysis clean |
| `live-check-c3-c4.txt` | `scripts/week05_agents_live_check.py --stage all` on the final JAR: 96 checks (lesson 29 plus lesson 30, including a headless Chromium session against the real console) |
| `c5/` | lesson 31: `mvn-clean-verify-c5.txt` (786 tests, 6 skipped, 0 failed), `live-check-c5.txt` (64 real-JAR checks for the three demos), `external-tier-b.txt` + the saved real responses (`external-open-meteo*.json/.txt`, `external-hn.json`, `external-github.json`) |
| `live-check-c3-c4.txt`, `live-check.txt`, `week04-live-check-regression.txt` | re-run on the final C5 JAR: 96/96, 31/31, 77/77 |
| `c6/` | lesson 32, clean clone of the acceptance commit: `mvn-clean-verify-c6.txt`, `test-summary.md`, `live-check-c5.txt` (64), `live-check-c3-c4.txt` (96), `live-check.txt` (31), `week04-live-check-regression.txt` (77), `quickstart-check.txt` (empty-workspace Quick Start), `security-audit.txt` |

Not here, because it was never done: any run with a real LLM, MCP server, webhook, Mem0 server or weather API.
