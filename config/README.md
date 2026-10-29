# config/

Configuration **templates** without credentials, and tool configuration.

| File | Purpose |
|---|---|
| `env.example` | the environment variables OryxOS reads, with their defaults and empty secrets |
| `checkstyle/checkstyle.xml` | Checkstyle rules used by the Maven build |

Spring Boot automatically loads `./config/application*.yaml` (and `.properties`) from the working directory. Nothing here is named `application*` on purpose, so a template can never silently change the runtime configuration. Real settings go in an untracked file or in environment variables; see [docs/operations/data-and-secrets.md](../docs/operations/data-and-secrets.md).
