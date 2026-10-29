# OryxOS Gate 1 · Prometheus endpoint fix (v1.1)

## Source of the failure

The Maven reactor reaches `oryxos-boot` and starts Spring Boot, but
`GET /actuator/prometheus` resolves as a missing resource instead of an
Actuator endpoint. Health and OpenAPI are already working. The old
`GlobalExceptionHandler` translates missing static resources into 500,
which obscures the underlying missing endpoint (normally 404).

The existing POM declared `micrometer-registry-prometheus` under
`oryxos-web`, but `/actuator/prometheus` still wasn't registered. This
revision makes the Actuator and Prometheus registry dependencies **direct
startup dependencies of `oryxos-boot`**, explicitly enables the export,
adds a fast wiring assertion for `PrometheusMeterRegistry` and maps
unmatched resources to 404.

## Changes

- `oryxos-web/pom.xml`: removes Actuator + Prometheus registry from the web library.
- `oryxos-boot/pom.xml`: adds these dependencies in the composition-root module.
- `oryxos-boot/src/main/resources/application.yaml`: explicitly exposes and enables Prometheus.
- `oryxos-boot/src/test/java/io/oryxos/OryxOsBootSmokeTest.java`: asserts Prometheus registry is present.
- `oryxos-web/src/main/java/io/oryxos/web/api/GlobalExceptionHandler.java`: a missing resource returns 404, not 500.
- Root `pom.xml`: removes an unsupported `maven-checkstyle-plugin` encoding parameter.

## Instructions (Git Bash)

Backup old workspace, then extract this ZIP into `~/Downloads/OryxOS_V1`.
This ZIP already contains one top-level folder `OryxOS_Gate1_SpringBoot_v1_1`.

```bash
cd ~/Downloads/OryxOS_V1/OryxOS_Gate1_SpringBoot_v1_1
ls pom.xml
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

When successful, run:

```bash
mvn -B -ntp -pl oryxos-boot -am package -DskipTests
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar
```

Use another terminal:

```bash
curl -i http://127.0.0.1:8080/actuator/health
curl -i http://127.0.0.1:8080/actuator/prometheus
curl -i http://127.0.0.1:8080/v3/api-docs
```

Expected for both Actuator URLs: HTTP 200. The Prometheus response
should contain `jvm_` names. Do **not** disable or skip the test.

If it still fails, run the diagnostics below and send the output:

```bash
mvn -B -ntp -pl oryxos-boot -am dependency:tree -Dincludes=io.micrometer:micrometer-registry-prometheus
mvn -B -ntp -pl oryxos-boot -am test -Dtest=OryxOsBootSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

This correction has been statically checked (XML parse, Java source layout)
but cannot be claimed as full `mvn verify` success until run in your Maven environment.
