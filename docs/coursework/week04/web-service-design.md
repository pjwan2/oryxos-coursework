# Web service and first admin console (lesson 26, plus the session list of lesson 27)

Covers the REST API, the unified error format, OpenAPI (Swagger), Prometheus, the Vue 3 admin console and the current local-access restriction.

## 1. Goals and non-goals

Goals: expose the runtime over HTTP with the same engine as the CLI; give the operator a first read-mostly management page; keep one deployable JAR.

Non-goals (not built, on purpose): authentication and users, agent create/edit/delete (lesson 30), streaming responses, a second web server, server-side rendering.

## 2. Layers

```
browser (Vue console)  /  curl / scripts
          |  HTTP, JSON envelope {code, message, data, timestamp}
   TraceIdFilter (X-Trace-Id) -> Controllers (thin) -> AgentInvoker / ports -> AgentService, stores
          |                                      \-> GlobalExceptionHandler (the only place errors become HTTP)
   AdminUiConfiguration + AdminIndexController (static Vue build under /admin/)
```

Controller rule (checked by `ArchitectureRulesTest` R5): a controller validates, calls a core port or `AgentInvoker`, maps the answer. The web module cannot import storage, provider, tool, memory or scheduler classes.

Virtual threads are on (`spring.threads.virtual.enabled: true`), the server binds `127.0.0.1`.

## 3. Endpoints

| Controller | Endpoint | Notes |
|---|---|---|
| `SessionApiController` | `POST /api/v1/sessions` | `{profile, userId?}`; 201 new, 200 existing (id is deterministic) |
| | `GET /api/v1/sessions?limit=` | lesson 27: latest activity first, default and max **100**, summary with message count and the last user message (80 chars) |
| | `GET /api/v1/sessions/{id}?limit=` | at most the latest **100** messages, plus `totalMessages` and `truncated` |
| | `POST /api/v1/sessions/{id}/messages` | `{content}`, at most **32 KiB** of UTF-8; session must exist |
| | `DELETE /api/v1/sessions/{id}` | removes the conversation; audit rows stay |
| `AgentApiController` | `POST /api/v1/agents/{name}/invoke` | `{message, userId?}`, channel `api` |
| `ProfileApiController` | `GET /api/v1/profiles` | no notify targets, no sandbox paths, no settings |
| `MemoryApiController` | `GET /api/v1/memory?limit=` | backend name, CORE, newest ARCHIVAL |
| `ToolApiController` | `GET /api/v1/tools`, `GET /api/v1/tool-calls` | registry with JSON Schema; audit rows with filters |
| `SystemApiController` | `GET /api/v1/health`, `GET /api/v1/info` | health is 503 if the database is unreachable; info has counts and switches, no configuration values |
| `ScheduleApiController` | four endpoints | see [scheduler-design.md](scheduler-design.md) |

Beyond the course list: `/api/v1/tool-calls` (the console needs it), and the lab endpoints `/api/v1/labs/**` from lesson 17 are unchanged.

## 4. Error format

One envelope for success and failure. Status mapping (`GlobalExceptionHandler`):

| Status | When | Text |
|---|---|---|
| 400 | blank/oversized message, bad session/profile/user id, unreadable JSON (the hint mentions UTF-8, a real Windows `curl` problem), bad parameter | our own fixed sentence, never an echo of the input |
| 403 | not a local caller | `Management writes are limited to local access` |
| 404 | unknown session, profile/agent, route | `Session not found`, `Unknown agent profile`, `Resource not found` |
| 405 / 415 | wrong method / content type | fixed text |
| 409 | scheduler: already running, scheduler disabled | our text |
| 500 | anything unexpected, including a configuration fault found while running an agent | always `Internal server error`; the exception goes to the server log only |
| 503 | model unreachable or overloaded; memory backend failure; health when the database is down | `Model provider unavailable`, `Memory backend unavailable`, `Service unavailable` |
| 504 | model timeout | `Model provider timed out` |

`ModelFailures` (provider module) sorts a provider failure into timeout (socket/HTTP timeout, HTTP 408/504 → 504) or unreachable (connect failure, I/O error, `TransientAiException`, 429/5xx → 503) and **drops the original message**, because provider messages can contain keys and URLs. Anything else (for example a rejected API key) stays a generic failure → 500. These mappings were tested with synthetic exceptions, not with a real provider outage.

## 5. Observability and API documentation

- `X-Trace-Id` on every response (`TraceIdFilter`), also in the JSON logs through MDC.
- OpenAPI document `/v3/api-docs` and Swagger UI `/swagger-ui.html` (springdoc 2.10.0; controllers carry `@Tag`). The live check asserts the document lists at least 10 paths and the UI page loads.
- Prometheus at `/actuator/prometheus` (Micrometer); actuator exposes `health,info,metrics,prometheus`. The live check asserts the output contains `jvm_memory_used_bytes`.
- SpringDoc's own start-up warning that both endpoints are on by default is still printed; they are not disabled per environment.

## 6. Admin console

Vue 3.5 + Vue Router + Vite 6, plain JavaScript, source in `oryxos-web/src/main/frontend/`, build output in `oryxos-web/src/main/resources/static/admin/` (git-ignored), `base: '/admin/'`. Spring Boot serves it from the same JAR:

- `/admin` redirects to `/admin/`; `AdminIndexController` returns the entry page; the resource handler for `/admin/**` returns a real file if there is one, `index.html` for a path without a file extension (so reloading `/admin/scheduler` works), and 404 for a missing file. `/api/**` is untouched.
- The web module's build fails if `static/admin/index.html` does not exist; `oryxos-web/src/main/frontend/scripts/check-build.mjs` fails the npm build on external URLs, source maps or credential-looking strings. Fonts are bundled from npm packages, nothing is loaded from a CDN.

| Page | Reads | Notes |
|---|---|---|
| Sessions | `/sessions`, `/sessions/{id}`, `/tool-calls?sessionId=` | list, messages, tool calls of the session; read-only |
| Profiles | `/profiles` | read-only |
| Tools | `/tools` | description and parameter table from the schema; read-only |
| Memory | `/memory` | backend, CORE, ARCHIVAL; read-only |
| System | `/info`, `/health` | read-only; shows the "no authentication" notice |
| Scheduler | `/schedules`, `/schedules/{id}/executions`, `/tool-calls`; writes `POST .../run`, `PUT /schedules/{id}` | run now (with confirmation) and enable/disable |

All data comes from the API (no data in the source). Every page has loading, empty and error states; an error shows the server's sentence, HTTP status and trace id, with a retry button.

**Scheduler result display.** After a run the page shows *Technical execution* (did the run finish or throw) and *Tool audit* (what happened to each tool call in the run's session and time window) as two separate lines. A sandbox refusal is shown in red as "denied by the sandbox" with the text that the tool action was **not** carried out and that a finished run is not proof that a notification was sent or an operation performed. It is never shown as success. Running a task that is disabled needs a confirmation that says a manual run is still allowed.

## 7. Local-access restriction (not authentication)

The server binds `127.0.0.1`. In addition `LocalAccess` guards the session endpoints (read and write), memory, tool-calls, agent invoke and the two scheduler writes: the TCP peer must be loopback, `X-Forwarded-For`/`Forwarded` must be absent (a proxy would hide the real peer), an `Origin` header must name a local host (stops a web page in the user's browser calling the API cross-site) and the `Host` header must be local (stops DNS rebinding). Profiles, tools, system and scheduler reads are not guarded because they carry no conversation or memory content and the port is loopback only.

This is a stop-gap. Any program or user on the same machine can call the API; there are no users, tokens or roles. **Do not publish this port**, do not put it behind a proxy, and do not describe it as secured or authenticated.

## 8. Evidence

| Evidence | Where |
|---|---|
| Controller slices (status codes, limits, envelope, no leak, local-only) | `SessionApiControllerTest` 22, `AgentApiControllerTest` 6, `QueryApiControllersTest` 11, `ScheduleApiControllerTest` 17 |
| API against real SQLite, comparing responses with table rows | `RestApiIntegrationTest` 11 |
| Serving the console, deep-link reload, `/api` untouched, path tricks | `AdminUiTest` 6 |
| Real fat JAR as a child process, console + API + restart | `WebSmokeIT` 5 (failsafe) |
| Provider failure classification | `ModelFailuresTest` 5, `ProviderServiceTest` |
| Front end | vitest 28 tests (states, API client, pages, scheduler outcome logic) |
| Live HTTP checks incl. 400/403/404/500, OpenAPI, Swagger, Prometheus, `/admin/` | `evidence/live-check.txt` |
| Headless Chrome run of all six pages with screenshots and SQLite comparison | `evidence/b4-live-run/` (automated; **not** a human review) |

## 9. Limitations

No authentication (section 7); 503/504 only verified with synthetic exceptions; concurrent messages to one session can overwrite each other; no pagination or search in the console (lists are capped at 100); session detail has no timestamps; the console has no delete or send actions although the API has them; the tool audit shown next to a scheduled run is matched by session and time window. See [known-limitations.md](known-limitations.md).
