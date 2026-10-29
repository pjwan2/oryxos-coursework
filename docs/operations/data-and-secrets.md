# Data and secrets

Four kinds of content live around this repository. Keep them apart.

| | Kind | Examples | In Git? |
|---|---|---|---|
| **A** | Source and configuration templates | `oryxos-*/src`, `pom.xml`, `config/env.example`, `config/checkstyle/`, `.oryxos/profiles/*.yaml` (demo agents), `examples/` | **yes** |
| **B** | Local runtime data | `*.db`, `*.db-shm`, `*.db-wal`, `*.bak` (pre-migration backups), `.oryxos/memory/`, `.oryxos/workspace/`, `*.log` | **never** |
| **C** | Generated build output | `**/target/`, `**/node_modules/`, `oryxos-web/src/main/resources/static/admin/`, `deliverables/*.zip` | **never** (regenerate) |
| **D** | Course evidence | `docs/coursework/week-*/evidence/`, `deliverables/week-*/` | **yes** (small text and PNG files only; paths of the author's machine replaced by `<HOME>`) |

## Secrets

- API keys and tokens (`DEEPSEEK_API_KEY`, `MEM0_API_KEY`, webhook tokens in a Profile's `notify_channels`) are supplied through the environment or an untracked file. There are none in the repository; `scripts/package_week04.py` scans every packaged file for key-like strings and refuses to package if it finds one.
- `.env` and `.env.*` are ignored (only `.env.example` is allowed). `config/env.example` lists variable names with empty values.
- Spring Boot loads `./config/application*.yaml` from the working directory automatically, so templates in `config/` are deliberately **not** named `application*`. Do not put real values into any file in `config/` that is tracked.
- Profiles are tracked. Do not write a webhook URL with a token or any credential into a tracked Profile; reference the environment instead.
- The sandbox's error messages and the audit tables are sanitised (no URL path or query, no token-like values), the REST layer never returns exception text, and the admin console only shows server messages. Do not add logging of request bodies or provider responses.

## Checking before a commit

```bash
git status --short                                  # nothing unexpected?
git ls-files | grep -E "\.(db|bak|log)$|(^|/)\.env|node_modules|/target/|static/admin"   # must print nothing
git diff --cached | grep -iE "api[_-]?key|token|password|secret"                      # read every hit
git diff --check                                    # whitespace errors
```

## If something sensitive was committed

Do not just delete it in a new commit: it stays in history. Rotate the credential first, then ask for help rewriting history; never force-push without agreeing it.

## Local data and moving the repository directory

`.oryxos/` and `oryxos-dev.db` are resolved relative to the working directory, not to the repository path, so renaming or moving the directory does not break them: move the directory with its untracked data, or copy the files listed in category B.
