# Documentation index

| Folder / file | What it holds |
|---|---|
| [architecture/](architecture/overview.md) | How the system fits together: [overview](architecture/overview.md), [module boundaries](architecture/module-boundaries.md), [repository and workspace layout](architecture/workspace-layout.md) |
| [decisions/](decisions/README.md) | Numbered design decisions with costs and open questions: [design-decisions.md](decisions/design-decisions.md) |
| [coursework/](coursework/README.md) | Per-week course material: [week-03](coursework/week-03/README.md), [week-04](coursework/week-04/README.md) (design docs, requirements matrix, acceptance report, limitations, evidence), [week-05](coursework/week-05/README.md) (not started) |
| [operations/](operations/runbook.md) | Current run book and the rules for data and secrets: [runbook](operations/runbook.md), [data-and-secrets](operations/data-and-secrets.md), [restructure-verification](operations/restructure-verification/README.md) (gate log of the repository reorganisation) |
| [coursework/iteration-log.md](coursework/iteration-log.md) | One-page index of the weekly iterations: scope, commits, test totals, tag and publication status per week |
| [history/](history/README.md) | Superseded run books and old fix notes, kept unchanged |
| [course-progress.md](course-progress.md) | Chronological log of stages A, B1 to B4 with the evidence of each (Chinese). A log: it records what was true at the time |

Other places: [`../README.md`](../README.md) (project front page), [`../CONTRIBUTING.md`](../CONTRIBUTING.md), [`../scripts/README.md`](../scripts/README.md), `../deliverables/` (generated course submissions).

Order of trust when documents disagree: the code and tests, then `architecture/`, `decisions/`, `operations/` and the newest `coursework/week-*`, then `course-progress.md`, then `history/`.

Language: design and operations documents are English; submission-facing reports for the course (requirements matrix, acceptance report, limitations) and the progress log are Chinese.

Check the links in these documents with `python scripts/check_doc_links.py`.
