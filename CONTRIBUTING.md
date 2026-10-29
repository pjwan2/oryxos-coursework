# Contributing

This is a coursework repository maintained by one person, but it is run like a small project so that it stays reviewable.

## Setup and the gate

Requirements and commands: [README](README.md#requirements) and [docs/operations/runbook.md](docs/operations/runbook.md). A change is ready when this passes from a clean checkout:

```bash
cd oryxos-web/src/main/frontend && npm ci && npm test && npm run build && cd ../../../..
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
git diff --check
```

Rules of the gate:

- Never skip tests (`-DskipTests`) to get green; never disable or add exclusions to Spotless, Checkstyle, PMD or SpotBugs. Fix the code. (Several findings in this project were fixed by changing code shape, for example the `discard` method name on the session store; the reasons are in the commit messages and [design decisions](docs/decisions/design-decisions.md), D-22.)
- Do not delete or weaken an existing test or assertion. If behaviour changes on purpose, change the test in the same commit and say why.
- `ArchitectureRulesTest` enforces the module boundaries in [docs/architecture/module-boundaries.md](docs/architecture/module-boundaries.md). A deliberate change to a boundary changes that document and the test together.

## Branches and commits

| Prefix | Use |
|---|---|
| `feature/` | new behaviour |
| `fix/` | bug fixes |
| `chore/` | structure, tooling, docs, no behaviour change |
| `release/` | frozen submission or release states |

Lower-case, hyphenated: `feature/spring-ai-react-integration`, `chore/repository-professionalization`. Commit subjects are imperative and say what and why in the body; stage commits are named `Phase <stage>: ...`. Do not push or open anything on a remote without the owner's say-so.

## Naming

| Thing | Style |
|---|---|
| Java classes | `PascalCase`; methods and fields `camelCase`; packages lower-case (`io.oryxos.<module>`) |
| Maven artifacts | `oryxos-<module>` (fixed by the course layout) |
| Tests | unit and slice tests `*Test` (surefire); tests that need the built JAR `*IT` (failsafe) |
| Markdown | `kebab-case.md`; standard names stay (`README.md`, `CONTRIBUTING.md`) |
| YAML | `kebab-case.yaml` (agent profiles are named after the agent) |
| Python scripts | `snake_case.py`; shell scripts `kebab-case.sh` |
| Vue components | `PascalCase.vue`; JavaScript modules `camelCase.js` |

Public Java API and Spring bean names are not renamed for tidiness (for example `Lesson17RuntimeConfiguration` and the bean `springAiAgentService` stay).

## Where things go

| Path | Holds |
|---|---|
| `oryxos-*/` | the nine modules (source, tests); `oryxos-web/src/main/frontend` is the Vue console |
| `docs/architecture`, `docs/decisions`, `docs/operations` | current, maintained documentation |
| `docs/coursework/week-NN` | what belongs to a course week: designs, reports, `evidence/` |
| `docs/history` | superseded documents, kept unchanged |
| `examples/` | public agent examples (no secrets) |
| `config/` | configuration templates without credentials, Checkstyle rules |
| `scripts/` | build, verification, packaging and maintenance scripts (see [scripts/README.md](scripts/README.md)) |
| `deliverables/week-NN` | submission material generated from `docs/` for a course week |
| `.oryxos/` | runtime working directory: only `profiles/*.yaml` demo agents are tracked |

What may and may not be committed: [docs/operations/data-and-secrets.md](docs/operations/data-and-secrets.md).

## Documentation rules

- Do not rewrite an old report or its numbers so that it fits new paths or a better story. Add a note or a new document; the path map is in [docs/coursework/README.md](docs/coursework/README.md).
- State what was verified and how. Things never verified (real LLM, MCP, Webhook, Mem0, the four symlink tests, human review of the console) stay labelled as such until they are.
- Check links with `python scripts/check_doc_links.py`.
