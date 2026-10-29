# OryxOS — course increment B4 (REST API + Vue admin console)

Coursework artifact built stage by stage: JDK 21, Spring Boot 3.5.x, Spring AI 1.1.4 and SQLite, nine Maven modules. It is NOT a finished Agent OS and has **no authentication**: run it only on a machine you trust. The server binds to `127.0.0.1` and refuses writes and sensitive reads that do not come from the local machine; that is a stop-gap, not security you can rely on.

Progress, evidence and open items per stage: [`docs/course-progress.md`](docs/course-progress.md).

## Build (reproducible)

Needs JDK 21, Maven 3.9+, Node 20.19+ and npm.

```bash
cd oryxos-web/src/main/frontend
npm ci            # exact versions from package-lock.json
npm test          # vitest
npm run build     # writes oryxos-web/src/main/resources/static/admin/ and checks it
cd ../../../..
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

`scripts/build-all.sh` runs the same steps. The console output is git-ignored; `oryxos-web` fails the build with an explanation if it has not been built, so a JAR can never ship without it.

## Run

```bash
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar
```

- Admin console: <http://127.0.0.1:8080/admin/> (Sessions, Profiles, Tools, Memory, System, Scheduler)
- REST API: `/api/v1/**` (OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`)
- Prometheus: `/actuator/prometheus`

Profiles are read from `.oryxos/profiles` (override with `ORYXOS_PROFILE_DIR`), the database is `./oryxos-dev.db` (`ORYXOS_JDBC_URL`).

Live front-end editing: run the JAR on 8080, then `npm run dev` in the frontend folder (port 5173, proxies `/api`).

## API summary

| Method and path | Purpose |
|---|---|
| `POST /api/v1/sessions` | create a session `{profile, userId?}` (201, or 200 if it exists) |
| `GET /api/v1/sessions` | latest 100 sessions by last activity |
| `GET /api/v1/sessions/{id}` | one session with its latest 100 messages |
| `POST /api/v1/sessions/{id}/messages` | send `{content}` (max 32 KiB UTF-8) |
| `DELETE /api/v1/sessions/{id}` | remove the stored conversation (audit rows stay) |
| `POST /api/v1/agents/{name}/invoke` | one call `{message, userId?}` |
| `GET /api/v1/profiles`, `/tools`, `/memory`, `/tool-calls` | read-only queries |
| `GET /api/v1/health`, `/info` | probe and status (health is 503 if the database is down) |
| `GET /api/v1/schedules`, `/{id}/executions`, `POST /{id}/run`, `PUT /{id}` | scheduler (B3) |

Errors use one envelope `{code, message, data, timestamp}`: 400 bad input, 404 unknown session/profile, 403 not local, 409 scheduler conflict, 500 generic text only, 503 model/memory/database unavailable, 504 model timeout. Every response carries `X-Trace-Id`.
