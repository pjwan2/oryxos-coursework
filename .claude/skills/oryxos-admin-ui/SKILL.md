---
name: oryxos-admin-ui
description: Conventions for the OryxOS Vue 3 admin console (oryxos-web/src/main/frontend). Use when adding or changing an admin page, component or style, building the console into the Spring Boot JAR, or extending it for lesson 30 (agent create/edit/delete).
---

# OryxOS admin UI conventions

The console is a small engineering tool, not a product site. Keep it plain, fast and honest.

## Where things live

| What | Path |
|---|---|
| Source | `oryxos-web/src/main/frontend/` (Vue 3 + Vite, plain JavaScript) |
| Build output | `oryxos-web/src/main/resources/static/admin/` (git-ignored, regenerated) |
| URL | `/admin/` on the same Spring Boot JAR; Vite `base` is `/admin/` |
| API | `/api/v1/**`, same origin, envelope `{code, message, data, timestamp}` |

Build and check, in this order:

```
cd oryxos-web/src/main/frontend
npm ci
npm test            # vitest, no browser needed
npm run build       # vite build + scripts/check-build.mjs
cd ../../../..
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

`oryxos-web` refuses to build (enforcer rule) when `static/admin/index.html` is missing, so a JAR
can never ship without the console. For live editing run Spring Boot on 8080 and `npm run dev`
(port 5173, proxies `/api` to 127.0.0.1:8080).

## Design tokens

No `website/.vitepress/theme/custom.css` exists in this project, so the course tokens are the
source of truth. They are CSS variables in `src/style.css`; use the variables, never hard-code hex
values in components.

| Token | Value | Use |
|---|---|---|
| `--bg` | `#000000` | page background |
| `--card` | `#111111` | cards, tables, buttons |
| `--border` | `#222222` | all 1px borders |
| `--accent` | `#f97316` | active nav, primary button, user-message edge, links |
| `--text` | `#f5f5f5` | primary text |
| `--muted` | `#a3a3a3` | secondary text, table headers |
| `--ok` / `--warn` / `--danger` | `#22c55e` / `#eab308` / `#ef4444` | status only, never decoration |

Fonts: Inter for text, JetBrains Mono for ids, cron, JSON and code. Both come from the npm packages
`@fontsource-variable/*` and are bundled into the JAR. Do not add Google Fonts or any CDN link:
the console must work offline and `check-build.mjs` fails the build on external URLs.

Layout: fixed left nav (200px), content max-width is the viewport, 8px radius, 16px card padding.
No animations, no icons library, no charts.

## Page rules (apply to every page, including lesson 30)

1. **Real data only.** Every value on screen comes from an API call. No mock arrays, no
   placeholder rows in the shipped code. Test stubs live in `tests/` only.
2. **Three states, always.** Wrap results in `<AsyncState>` driven by `useResource()`:
   `loading`, `empty` (with a sentence saying what to do next) and `error`. The error box shows
   the server's `message`, the HTTP status and the `X-Trace-Id`, plus a Retry button.
3. **Never show internals.** Show only what `ApiError.message` carries (the server's safe text).
   Do not print `error.stack`, raw response bodies, JDBC URLs, file system paths of the server,
   API keys, webhook URLs or tokens. The API does not send them; do not add fields that would.
4. **Read-only unless the lesson says otherwise.** Sessions, Profiles, Tools, Memory and System
   are read-only. Writes (Scheduler: run, enable/disable) are explicit buttons that name the
   effect first. Lesson 30 may add agent create/edit/delete: put those behind a confirm step and
   show the server's validation message verbatim.
5. **Technical status is not business success.** For anything that runs an agent, show the
   technical execution result and the tool audit result as two separate facts
   (`src/scheduler.js`). A sandbox refusal is never shown as success, as a sent notification or as
   a completed operation.
6. **Be honest about the missing authentication.** The console and API are for trusted local use
   only (server binds 127.0.0.1; writes and sensitive reads check loopback/Origin/Host). Keep the
   notice in the nav and on the System page. Never call this "secure" or "authenticated".
7. **Routes.** History mode under `/admin/`. Path params must not contain a dot (the server
   treats `name.ext` as a file and answers 404); keep scheduled task ids (`profile.id`) in page
   state or query strings, not in the path.
8. **Manual scheduler run.** Always say that a manual run is allowed even when the task is
   disabled, and require a confirm click.

## Adding a page

1. `src/views/XView.vue` using `useResource` + `AsyncState`.
2. Add the route in `src/router.js` and the link in `src/App.vue`.
3. Add a test in `tests/views.test.js` for loading/empty/error and one rendering test with a
   stubbed `fetch`.
4. Add the backend endpoint first if needed: controller stays thin (validate, call a service,
   map the response), execution goes through the single `AgentService`, errors go through
   `GlobalExceptionHandler` (400/404/500/503/504, no internal text in 500).
5. Rebuild, run `npm test`, `npm run build`, then the Maven gate.

## What not to do

- No second web server, no Node process in production, no SSR.
- No state library, UI kit or TypeScript migration just for style; keep dependencies minimal.
- No polishing animations or themes; B4 closed the visual work.
- Do not claim a page was "checked in a browser" unless it really was; say which automated checks
  ran (vitest, MockMvc, fat-JAR smoke, headless screenshot).
