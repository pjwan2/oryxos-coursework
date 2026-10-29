# Workspace layout and file classification

Date of review: after stage B4, commit `e6c76c0`. Nothing was moved, renamed or deleted outside this repository. Inside the repository two historical notes and the B4 evidence were moved with `git mv` (history kept).

## The one main project

`C:/Users/USER/Downloads/OryxOS_V1/OryxOS_Gate2C_Session_Audit_v1_5/` is the only folder that is a Git repository (branch `feature/spring-ai-react-integration`; stage commits A `9c07dc0`, B1 `d57fcd2`, B2 `67e7364`, B3 `4c1e3cd`, B4 `e6c76c0`). It is designated as the main project by:

- `C:/Users/USER/Downloads/OryxOS_V1/OryxOS.code-workspace` (a VS Code workspace with only this folder; open it with *File > Open Workspace from File*), and
- `C:/Users/USER/Downloads/OryxOS_V1/README.md` (a table saying what every sibling folder is).

Both files are outside the repository on purpose: the parent folder is not under Git.

## Classification of what is inside the repository

| Kind | Paths | In Git? |
|---|---|---|
| **Source** | `oryxos-*/src/main`, `oryxos-web/src/main/frontend/{src,tests,scripts,public,index.html,package*.json,vite.config.js}` | yes |
| **Tests** | `oryxos-*/src/test`, `oryxos-web/src/main/frontend/tests` | yes |
| **Build configuration** | `pom.xml` (parent and modules), `config/checkstyle`, `.github/workflows`, `scripts/*` | yes |
| **Runtime configuration (examples)** | `.oryxos/profiles/*.yaml` (demo agents used by tests and the README), `examples/profiles/*.yaml` | yes |
| **Documentation** | `README.md`, `docs/**` | yes |
| **Evidence** | `docs/coursework/week04/evidence/**`, `deliverables/week04/**` | yes (small text and PNG files) |
| **Historical notes** | `docs/history/{PROMETHEUS_FIX,SPOTBUGS_FIX}.md` (moved from the repository root) | yes |
| **Generated** | `**/target/`, `oryxos-web/src/main/resources/static/admin/` (Vue build), `oryxos-web/src/main/frontend/node_modules/` | **no** (`.gitignore`) |
| **Local data, never commit** | `oryxos-dev.db`, any `*.db`, `.oryxos/memory/`, `*.bak` (pre-migration backups), `*.log` (`server.log`, `diagnostic-8096.log`), `.env*` | **no** (`.gitignore`; checked with `git ls-files`) |
| **Secrets** | none in the tree; `DEEPSEEK_API_KEY` and `MEM0_API_KEY` are read from the environment only | n/a |
| **Packaging output** | `deliverables/*.zip` | no (`.gitignore`; produced by `scripts/package_week04.py`) |

`.git/gate2d_before_install/` inside the repository's `.git` is a backup left by the Gate 2D script. It is part of the repository's private data, is not tracked, and was left alone.

## Folders next to the main project (not changed)

| Folder | Size | What it is | Proposed handling |
|---|---|---|---|
| `OryxOS_Gate1_SpringBoot_v1_1/` | 37 MB | Gate 1 skeleton, no Git | archive |
| `OryxOS_Gate2_Provider_v1_3/` | 88 MB | Provider checkpoint, no Git | archive |
| `OryxOS_Gate2B_ReAct_v1_4/` | 88 MB | ReAct checkpoint, no Git | archive |
| `OryxOS_When_Homework_Implementation_v1/` | 0.6 MB | A separate "When" homework pack with its own roadmap and evidence template; useful as a reading aid for later weeks | keep, or archive |
| `OryxOS_Gate2D_apply_to_existing_v15.py` | 32 KB | one-off script that applied Gate 2D to v1.5 | archive |
| `oryxos-dev.db` (0 bytes) | 0 | stray empty file | safe to delete after you confirm |
| `.vscode/settings.json` | tiny | one Java setting | keep |

The Gate folders have no Git history of their own and were not compared with the main project's history, so **treat each as the only copy of its exact state.** Hence the proposal below is "archive", not "delete".

## Proposed archive plan (NOT executed; needs your approval)

If you approve, these commands move the historical material into one folder without deleting anything. Run them yourself, or tell me to run them.

```powershell
cd C:\Users\USER\Downloads\OryxOS_V1
New-Item -ItemType Directory _archive | Out-Null
Move-Item OryxOS_Gate1_SpringBoot_v1_1      _archive\
Move-Item OryxOS_Gate2_Provider_v1_3        _archive\
Move-Item OryxOS_Gate2B_ReAct_v1_4          _archive\
Move-Item OryxOS_Gate2D_apply_to_existing_v15.py _archive\
# Optional, after checking it is really empty and unused:
# Remove-Item oryxos-dev.db
```

Before moving, close VS Code windows and any terminal whose working directory is inside those folders, and make sure no `java -jar` process started from them is running (a running JVM locks its JAR on Windows). Moving does not touch the main project, its `.git` or any database.

Optionally compress `_archive` to a `.zip` outside the workspace for a backup before anything else.

## Safety rules that were followed

- No `git reset --hard`, `git clean`, force operation or push.
- `.git` of the main project was not moved or renamed.
- No database, memory file, environment file or credential was read into a commit or deleted. `git ls-files` shows no `*.db`, `*.log`, `.env*`, `*.bak`, `node_modules` or `static/admin` entries.
- B4 work was already committed (`e6c76c0`) before this review started, so there were no uncommitted B4 changes to protect.
