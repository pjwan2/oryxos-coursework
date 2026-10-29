# Workspace and repository layout

Current as of the repository professionalization (branch `chore/repository-professionalization`). The previous version of this page (written at commit `e6c76c0`, week-4 delivery) is in Git history. Nothing outside this repository was moved or deleted.

## The one main project

The formal name of the repository directory is **`oryxos-coursework`** (project name: *OryxOS — Agent Runtime Engineering*). On this machine the directory is still called `OryxOS_Gate2C_Session_Audit_v1_5`, because Windows reported "Device or resource busy" when it was renamed (something, most likely an editor or terminal, holds it open). Nothing in the repository depends on the directory name: no source, configuration or script contains it, and the full gate was run from a clean clone named `oryxos-coursework` (see the professionalization section of [course-progress.md](../course-progress.md)).

To finish the rename yourself:

```powershell
# 1. close VS Code windows and terminals that have the folder open
cd C:\Users\USER\Downloads\OryxOS_V1
Rename-Item OryxOS_Gate2C_Session_Audit_v1_5 oryxos-coursework
# 2. fix the one-line workspace file next to it
(Get-Content OryxOS.code-workspace) -replace 'OryxOS_Gate2C_Session_Audit_v1_5','oryxos-coursework' | Set-Content OryxOS.code-workspace
```

`.git`, the untracked local data (`oryxos-dev.db`, `.oryxos/memory`, `node_modules`, `target`) and all relative paths keep working: runtime paths are resolved against the working directory, not against the repository path.

Branches: `feature/spring-ai-react-integration` plus `chore/repository-professionalization` for this reorganisation. Stage commits: A `9c07dc0`, B1 `d57fcd2`, B2 `67e7364`, B3 `4c1e3cd`, B4 `e6c76c0`, week-4 delivery `f31fd9f` to `6c1e643`.

The parent folder `OryxOS_V1/` is not a Git repository. It holds `OryxOS.code-workspace` (a VS Code workspace with only the main project) and a `README.md` classifying the sibling folders; both are outside the repository on purpose.

## Layout

```
oryxos-coursework/
  README.md  CONTRIBUTING.md  pom.xml  .gitignore  .gitattributes
  oryxos-core/ oryxos-provider/ oryxos-tool/ oryxos-memory/ oryxos-storage/
  oryxos-scheduler/ oryxos-cli/ oryxos-web/ oryxos-boot/        nine Maven modules
      oryxos-web/src/main/frontend/        Vue 3 + Vite admin console (source)
  config/            templates without credentials (env.example), Checkstyle rules
  examples/          public agent examples (profiles)
  scripts/           build, verification, packaging, maintenance (README.md lists them)
  docs/
    architecture/    overview, module boundaries, this page
    decisions/       design decision register
    coursework/      week-03/  week-04/ (designs, reports, evidence)  week-05/ (not started)
    operations/      runbook, data and secrets
    history/         superseded run books and fix notes, unchanged
    course-progress.md   chronological log of stages A, B1 to B4
  deliverables/week-04/   generated submission folder (the zips are not tracked)
  .oryxos/profiles/       runtime working directory: only demo agent profiles are tracked
  .github/workflows/ci.yml   .claude/skills/
```

## Classification

| Kind | Paths | In Git? |
|---|---|---|
| **A. Source and configuration templates** | `oryxos-*/src`, `pom.xml`, frontend `src`, `tests`, `public`, `package*.json`, `vite.config.js`; `config/`, `examples/`, `scripts/`, `.oryxos/profiles/*.yaml`, `.github/`, `.claude/skills/` | yes |
| **B. Local runtime data** | `oryxos-dev.db` and any `*.db*`, `*.bak`, `.oryxos/memory/`, `.oryxos/workspace/`, `*.log`, `.env*` | **no** (ignored) |
| **C. Generated build output** | `**/target/`, `**/node_modules/`, `oryxos-web/src/main/resources/static/admin/`, `deliverables/*.zip` | **no** (ignored) |
| **D. Course evidence** | `docs/coursework/week-*/evidence/`, `deliverables/week-*/`, `docs/course-progress.md` | yes |
| **Documentation** | `README.md`, `CONTRIBUTING.md`, `docs/**` (except evidence) | yes |

Details and the pre-commit checks: [../operations/data-and-secrets.md](../operations/data-and-secrets.md). `.oryxos/` keeps its runtime meaning (profile directory, Markdown memory, sandbox workspace); nothing about it changed.

`.git/gate2d_before_install/` inside `.git` is a backup left by the Gate 2D script; it is untracked and was left alone.

## What was moved in the reorganisation

All with `git mv` (history preserved), text unchanged. The authoritative list is `git show --stat -M <commit>`; the main moves:

| From | To |
|---|---|
| `docs/coursework/week04/` | `docs/coursework/week-04/` |
| `deliverables/week04/` | `deliverables/week-04/` |
| `docs/architecture/design-decisions.md` | `docs/decisions/design-decisions.md` (a one-line stub stays at the old path for old links) |
| `docs/COURSE_MAPPING.md` | `docs/coursework/week-03/course-mapping.md` |
| `docs/LESSON16_RUNBOOK.md`, `LESSON17_RUNBOOK.md`, `LESSON17_PHASE2_RUNBOOK.md`, `GATE2D_APPLY_AND_VERIFY.md` | `docs/history/lesson-16-runbook.md`, `lesson-17-runbook.md`, `lesson-17-phase2-runbook.md`, `gate2d-apply-and-verify.md` |
| `docs/history/PROMETHEUS_FIX.md`, `SPOTBUGS_FIX.md` | `docs/history/prometheus-fix.md`, `spotbugs-fix.md` |

Complete path map for older documents: [../coursework/README.md](../coursework/README.md).

## Folders next to the main project (not changed)

| Folder | Size | What it is | Proposed handling |
|---|---|---|---|
| `OryxOS_Gate1_SpringBoot_v1_1/` | 37 MB | Gate 1 skeleton, no Git | archive |
| `OryxOS_Gate2_Provider_v1_3/` | 88 MB | Provider checkpoint, no Git | archive |
| `OryxOS_Gate2B_ReAct_v1_4/` | 88 MB | ReAct checkpoint, no Git | archive |
| `OryxOS_When_Homework_Implementation_v1/` | 0.6 MB | Separate "When" homework pack with a roadmap and evidence template | keep, or archive |
| `OryxOS_Gate2D_apply_to_existing_v15.py` | 32 KB | one-off script that applied Gate 2D | archive |
| `oryxos-dev.db` (0 bytes) | 0 | stray empty file | delete after you confirm |

The Gate folders have no Git history of their own, so treat each as the only copy of its exact state: archive, do not delete. **The archive plan below has not been executed and needs your approval.**

```powershell
cd C:\Users\USER\Downloads\OryxOS_V1
New-Item -ItemType Directory _archive | Out-Null
Move-Item OryxOS_Gate1_SpringBoot_v1_1      _archive\
Move-Item OryxOS_Gate2_Provider_v1_3        _archive\
Move-Item OryxOS_Gate2B_ReAct_v1_4          _archive\
Move-Item OryxOS_Gate2D_apply_to_existing_v15.py _archive\
```

Close editors and terminals first, and make sure no `java -jar` started from those folders is running (a running JVM locks its JAR on Windows).

## Safety rules followed

No `git reset --hard`, `git clean`, force operation or push; `.git` untouched; no database, memory file or credential read into a commit; no parent-folder project moved or deleted; old reports and evidence not rewritten.
