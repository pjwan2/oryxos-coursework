# Repository and workspace layout

Project: **OryxOS Coursework — AI Agent Runtime**. Repository: **`oryxos-coursework`**.

## How this repository came about

The code was developed stage by stage (A, B1 to B4, week-4 delivery) in a directory with a provisional name. It was then published as `oryxos-coursework` by making an **independent local clone** of the verified commit `c8b8513` (with its complete history, 14 commits), because Windows kept the original directory locked and it was not renamed in place. The original directory is kept, untouched, outside this repository as a local backup. Nothing in the repository depends on any directory name: no source, configuration or script contains one, and runtime paths are resolved against the working directory.

Stage commits: A `9c07dc0`, B1 `d57fcd2`, B2 `67e7364`, B3 `4c1e3cd`, B4 `e6c76c0`, week-4 delivery `f31fd9f` to `6c1e643`, repository reorganisation `3c9bb86`, `c8b8513`. Branch `submission/week-04` is the coursework submission of week 4; later weeks are developed on `feature/` branches and are not mixed into it.

## Layout

```
oryxos-coursework/
  README.md  CONTRIBUTING.md  pom.xml  .gitignore  .gitattributes
  oryxos-core/ oryxos-provider/ oryxos-tool/ oryxos-memory/ oryxos-storage/
  oryxos-scheduler/ oryxos-cli/ oryxos-web/ oryxos-boot/        nine Maven modules
      oryxos-web/src/main/frontend/        Vue 3 + Vite admin dashboard (source)
  config/            templates without credentials (env.example), Checkstyle rules
  examples/          public agent examples (profiles)
  scripts/           build, verification, packaging, security audit (README.md lists them)
  docs/
    architecture/    overview, module boundaries, this page
    decisions/       design decision register
    coursework/      week-03/  week-04/ (designs, reports, evidence)  week-05/ (not started)
    operations/      runbook, data and secrets, restructure verification
    history/         superseded run books and fix notes, unchanged
    course-progress.md   chronological log of stages A, B1 to B4 (Chinese)
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

Details and pre-commit checks: [../operations/data-and-secrets.md](../operations/data-and-secrets.md). `.oryxos/` keeps its runtime meaning (profile directory, Markdown memory, sandbox workspace).

## Earlier moves inside the repository

All with `git mv` (history preserved, text unchanged); authoritative list: `git show --stat -M 3c9bb86`. Full old-to-new path map: [../coursework/README.md](../coursework/README.md).

## Local parent folder (outside this repository)

On the author's machine the repository sits next to the earlier directories it grew out of. They are **not** part of this repository and were not changed:

| Folder | What it is | Proposed handling |
|---|---|---|
| original working directory (provisional name) | the directory this repository was cloned from, still has its own build output and local data | keep as backup |
| earlier checkpoints (Gate 1, Provider v1.3, ReAct v1.4) | no Git history of their own | archive, do not delete |
| a separate "When" homework pack | not part of OryxOS | keep or archive |
| a one-off patch script and an empty stray `oryxos-dev.db` | historical | archive / delete after confirming |

Archiving is a manual step that needs the owner's approval: close editors and terminals first, make sure no `java -jar` started from those folders is running (a running JVM locks its JAR on Windows), then move them into a single `_archive` folder with `Move-Item`.

## Safety rules followed

No `git reset --hard`, `git clean`, force operation; no history rewriting; no database, memory file or credential committed; no existing directory overwritten or deleted; old reports and evidence not rewritten.
