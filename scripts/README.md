# scripts/

Build, verification, packaging and maintenance scripts. Run them from anywhere; they locate the repository themselves.

| Script | What it does | Needs |
|---|---|---|
| `build-all.sh` | Front end (`npm ci`, `npm test`, `npm run build`) then Maven (`spotless:apply`, `clean verify`) | Node, JDK 21, Maven |
| `week04_live_check.py` | Starts the real JAR in a temporary directory and runs 77 checks on Memory, Sandbox, Scheduler (with a kill and restart), REST, Swagger, Prometheus and the console. `--jar <path>` required, `--out <file>` writes the transcript | Python 3.9+, JDK 21, a built JAR |
| `test_summary.py` | Per-module and per-class test counts from the surefire/failsafe XML, as Markdown (run after `mvn verify`) | Python 3.9+ |
| `package_week04.py` | Refreshes `deliverables/week-04/` from `docs/coursework/week-04/`, then writes `deliverables/oryxos-week-04-coursework.zip` (the submission folder only) and `deliverables/oryxos-week-04-source.zip` (a `git archive` of HEAD under `oryxos-coursework/`). Scans every file for databases, logs, `.env`, build output, keys and credential patterns and refuses to package on a hit. Never uploads or pushes | Python 3.9+, Git |
| `security_audit.py` | Scans tracked files **and the whole Git history** for secrets, tokens, webhook URLs, absolute local paths, e-mail addresses and file names that must never be committed; lists known fake test fixtures separately. Run before publishing | Python 3.9+, Git |
| `check_doc_links.py` | Fails if a relative Markdown link points at a missing file | Python 3.9+, Git |

Conventions: Python scripts are `snake_case.py`, shell scripts `kebab-case.sh`. The `week04_` and `_week04` names belong to the week-4 delivery and are kept so that the documents written then stay accurate.
