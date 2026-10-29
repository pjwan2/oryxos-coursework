# Run book

How to build, run, configure and look after OryxOS on one machine. Everything below was run on Windows 10 with JDK 21 and Git Bash; commands are bash. The server has no authentication: use it only on a machine you trust.

## 1. Build

Requirements: JDK 21, Maven 3.9+, Node ≥ 20.19 and npm.

```bash
cd oryxos-web/src/main/frontend
npm ci            # exact versions from package-lock.json
npm test          # vitest
npm run build     # writes oryxos-web/src/main/resources/static/admin/ and checks it
cd ../../../..
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

`scripts/build-all.sh` runs the same. `mvn verify` also runs `WebSmokeIT`, which starts the repackaged JAR as a separate process. If the Vue build is missing, the `oryxos-web` module fails with the command to run (a JAR without the console would start and then answer 404 on `/admin/`).

Windows note: a running `java -jar` locks the JAR; stop it before rebuilding.

## 2. Run

```bash
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar          # REST server + console on 127.0.0.1:8080
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar chat memory-demo "hello"   # CLI, one-shot
```

Run from the repository root (or any directory that contains the files named in section 3: relative paths are resolved against the working directory).

| URL | |
|---|---|
| `/admin/` | admin console |
| `/api/v1/health`, `/api/v1/info` | probes |
| `/swagger-ui.html`, `/v3/api-docs` | OpenAPI |
| `/actuator/prometheus` | metrics |

## 3. Configuration

Defaults are in `oryxos-boot/src/main/resources/application.yaml`. Override with environment variables or `--name=value` arguments. A template of the variables is [config/env.example](../../config/env.example) (no secrets in it).

| Setting | Variable | Default |
|---|---|---|
| port | `SERVER_PORT` | `8080` (bound to `127.0.0.1` in `application.yaml`) |
| database file | `ORYXOS_JDBC_URL` | `jdbc:sqlite:./oryxos-dev.db` |
| agent definitions | `ORYXOS_PROFILE_DIR` | `.oryxos/profiles` |
| memory backend | `ORYXOS_MEMORY_BACKEND` | `markdown` (`sqlite`, `mem0`) |
| Markdown memory file | `ORYXOS_MEMORY_FILE` | `.oryxos/memory/MEMORY.md` |
| scheduler on/off | `ORYXOS_SCHEDULER_ENABLED` | `true` (validation still runs when off) |
| sandbox ceiling | `oryxos.sandbox.read-roots`, `write-roots`, `commands`, `hosts`, `allow-private-networks` | only `.oryxos/workspace`, nothing else |
| real model | `DEEPSEEK_API_KEY` | empty: only the Mock model is registered. **Never run with a real key so far** |
| Mem0 | `MEM0_API_KEY`, `oryxos.memory.mem0-base-url` | unused. **Never run against a real Mem0** |

Agent definitions are YAML files in the profile directory, read once at startup (restart to change). See `examples/profiles/` for a sandboxed agent, a scheduled agent and a real-model payment example.

## 4. Where data lives

| What | Where | In Git |
|---|---|---|
| SQLite database (sessions, audits, memory table, schedules) | `./oryxos-dev.db` unless `ORYXOS_JDBC_URL` says otherwise | no |
| Pre-migration backups | `<db>.pre-v<N>-<timestamp>.bak`, next to the database, never deleted automatically | no |
| Markdown memory | `.oryxos/memory/MEMORY.md` | no |
| Sandbox workspace | `.oryxos/workspace/` | no |
| Agent definitions | `.oryxos/profiles/*.yaml` | yes (demo agents only) |
| Logs | stdout (JSON) | n/a |

More in [data-and-secrets.md](data-and-secrets.md).

## 5. Backup and restore

Stop the server, then copy the database file (and `.oryxos/memory/MEMORY.md` if the Markdown backend is used). Restore by copying back while stopped. On startup an existing database is upgraded by versioned migrations: it is first copied (`VACUUM INTO`), the upgrade is rehearsed on the copy with row counts compared, and only then applied. A database written by a newer version than the program, or with an altered migration history, is refused at startup.

## 6. Verify a running instance

```bash
python scripts/week04_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar   # starts its own JVM and temp files; 77 checks
curl -s http://127.0.0.1:8080/api/v1/health
```

## 7. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Maven fails in `oryxos-web` at `validate` with "The Vue admin console is not built" | run the npm steps of section 1 |
| `Database migration failed: path to '/c/Users/...' does not exist` | a Git-Bash style path was given to Java; use a Windows path (`C:/...`) in `ORYXOS_JDBC_URL` and other Java arguments |
| `Startup failed: ... settings.sandbox ...` | an invalid sandbox declaration in a Profile or in `oryxos.sandbox.*`; the message names the file and key |
| `400 Malformed request body` from curl on Windows | non-UTF-8 body (Git Bash sends the console code page); send UTF-8 |
| `403` on `/api/v1/sessions` etc. | the request was not from loopback, carried a forwarded or foreign `Origin`/`Host` header; call it directly on `127.0.0.1` |
| Rebuild fails to replace the JAR | the server is still running; stop it |
| Port in use | `SERVER_PORT=8081` or `--server.port=8081` |
