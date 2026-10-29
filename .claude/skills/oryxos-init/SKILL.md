---
name: oryxos-init
description: Initialize and verify OryxOS's nine-module engineering foundation.
---

# OryxOS Initialization Skill (foundation stage)

Before implementing business features, preserve the course architecture and follow these six phases:

1. **Skeleton:** build the 9-module Maven reactor, one `oryxos-boot` composition root, JDK 21.
2. **Logging and monitoring:** JSON SLF4J/Logback, per-request traceId, Actuator/Micrometer/Prometheus.
3. **HTTP and API:** Spring MVC, `/api/v1` response envelope, exception handling, Swagger UI.
4. **Formatting and conventions:** Spotless Google Java format, Checkstyle, PMD. Confirm Alibaba/P3C separately.
5. **Security scans:** SpotBugs in default `verify`; OWASP dependency-check under `security-check` profile pending NVD setup.
6. **CI and proof:** `mvn spotless:apply`; `mvn clean verify`; boot JAR; curl health, Prometheus, Swagger.

Do not change project tests or weaken validation to make red tests green. Do not add a real LLM key to source control.
This covers the foundation stage only. Provider, ReAct, tools, persistence, and scheduler are later stages.
