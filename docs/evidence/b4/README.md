# B4 evidence

Output of a manual verification run of the real fat JAR (`java -jar oryxos-boot-0.1.0-SNAPSHOT.jar`, port 8098, fresh SQLite file, Mock model) driven by headless Chrome. This is **not** part of `mvn verify` and is not run in CI; the automated gates are listed in `docs/course-progress.md`.

| File | What it is |
|---|---|
| `main-output.txt` | 27 browser/API checks on a fresh database |
| `after-restart-output.txt` | 5 checks after killing and restarting the JVM on the same database |
| `db-before.txt`, `db-after.txt` | SQLite contents behind the UI before and after the restart (identical) |
| `screenshots/*.png` | headless Chrome screenshots, 1280x900 (`main-*` first run, `after-restart-*` after the restart) |
| `verify.mjs`, `db.py` | the scripts that produced them. They are scratch tooling: paths to the author's temp directory are hard-coded, and `puppeteer-core` is not a dependency of the project (`npm i puppeteer-core` in a separate folder, set the Chrome path). |

Screenshots and the automated checks are not a substitute for a person looking at the pages.
