# Week-4 evidence index

All files are real output of commands run on the author's machine (Windows 10, JDK 21.0.2, Maven 3.9.16, Node 20.20.2). Local paths were replaced by `<HOME>`. Nothing here is edited by hand except that replacement and the removal of the `exit=` helper lines from `test-summary.md`.

| File | What it proves | Produced by |
|---|---|---|
| `clean-checkout/00-environment.txt` | The run started from a fresh `git clone` of commit `f31fd9f` with no `node_modules`, `static/admin` or `target`; tool versions | `scripts` / shell |
| `clean-checkout/01-npm-ci.txt` … `03-npm-build.txt` | `npm ci`, `npm test` (28 tests), `npm run build` + build check | npm |
| `clean-checkout/04-mvn-spotless-apply.txt` | `mvn spotless:apply` succeeds and changes nothing | Maven |
| `clean-checkout/05-mvn-clean-verify.txt` | Full `mvn -B -ntp clean verify`: nine modules SUCCESS, all tests, Checkstyle, PMD, SpotBugs, Spotless, failsafe `WebSmokeIT` | Maven |
| `test-summary.md` | Tests, skipped and failed per module and per class (502 / 4 / 0) | `scripts/test_summary.py` over the surefire/failsafe XML |
| `static-analysis-summary.txt` | The static-analysis lines extracted from the build log | grep |
| `clean-checkout/06-jar.txt` | The fat JAR and the `static/admin` files inside its nested web jar | Python |
| `live-check.txt` | 77 checks against the real JAR: Memory (markdown, sqlite, restart), Sandbox (with audit rows), Scheduler (with kill and restart), Web/Swagger/Prometheus/admin | `scripts/week04_live_check.py` |
| `clean-checkout/08-readme-default-start.txt` | `java -jar` with no arguments in the clean clone: health, profiles, a memory write, `/admin/`, Swagger, Prometheus, loopback listening address | curl |
| `final-release/` | **The final verification of the published commit** `f11f6f0` (fresh clone `oryxos-coursework`, 2026-10-30): npm, Maven `clean verify` (502 / 4 skipped / 0), fat-JAR manifest, default start, 77 live checks, headless-browser run of the six dashboard pages (`vue-dashboard/`), doc-link check, `git diff --check`, repository security audit. Described in [../acceptance-report.md](../acceptance-report.md) | npm, Maven, scripts |
| `b4-live-run/` | Headless-Chrome run of the six admin pages against the real JAR (27 + 5 checks), SQLite dumps before and after a restart, 13 screenshots. Automated, **not** a human review. Made on commit `e6c76c0` (B4); no console or API code has changed since | Puppeteer, see its README |

Not in here, because it was never done: any run with a real LLM, MCP server, webhook or Mem0 server; the four skipped symlink tests; a human walk-through of the console.
