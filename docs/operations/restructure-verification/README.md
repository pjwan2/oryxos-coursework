# Verification of the repository restructure

Output of the full gate run on a **fresh clone of commit `3c9bb86`** (branch `chore/repository-professionalization`) into a directory named `oryxos-coursework`, so that any dependency on the old directory name would have shown up. Local paths were replaced by `<HOME>` and terminal colour codes removed; nothing else was edited.

| File | Command / purpose | Result |
|---|---|---|
| `00-environment.txt` | clone, tool versions, nothing pre-built, and a search of the tracked runtime files for the old directory name | clean clone; old name occurs nowhere in source, poms, config, scripts, CI, `.oryxos` or examples |
| `01-npm-ci.txt`, `02-npm-test.txt`, `03-npm-build.txt` | `npm ci`, `npm test`, `npm run build` | exit 0; 28 front-end tests pass; build check ok |
| `04-mvn-spotless-apply.txt`, `04b-...` | `mvn -B -ntp spotless:apply` | exit 0; `git status` empty afterwards |
| `05-mvn-clean-verify.txt` | `mvn -B -ntp clean verify` | BUILD SUCCESS, nine modules, 2 min 39 s |
| `test-summary.md` | per module and class | 502 tests, 4 skipped, 0 failed: **identical to the pre-restructure baseline** (`../../coursework/week-04/evidence/test-summary.md`, commit `f31fd9f`), including `PathSecurityTest` 18 / 4 skipped |
| `07-live-check.stdout` | `scripts/week04_live_check.py` on the built JAR | 77/77 |
| `08-doc-links.txt` | `scripts/check_doc_links.py` | 0 broken links |
| `09-git-diff-check.txt` | `git diff --check` | clean |
| `10-sensitive-files.txt` | tracked files matching database, backup, log, key, `.env`, `node_modules`, `target`, `static/admin` | none |
| `11-package.txt` | `scripts/package_week04.py` | both zips produced, safety scan clean |
| `12-git-status-after-everything.txt` | `git status` after all of the above | empty |
| `13-default-start.txt` | `java -jar` with no arguments in the clone | health, `/admin/`, Swagger, Prometheus 200; profiles from `.oryxos/profiles`; a memory write and a session in SQLite; `oryxos-dev.db` and `.oryxos/memory/MEMORY.md` created and **ignored** by Git |

Not covered: the GitHub Actions workflow (`ci.yml`) was changed to build the front end first but was **not executed** (no remote, nothing pushed); the commands in it are the ones above.
