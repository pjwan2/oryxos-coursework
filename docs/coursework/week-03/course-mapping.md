# 第15节 -> 文件映射 / 真正完成的证据

| 老师的要求 | 对应文件 | 下一步应验证 |
|---|---|---|
| 九模块 Maven 工程，单个 Boot 入口 | `pom.xml` + 9 个子 POM + `OryxOsApplication.java` | `mvn clean verify`，唯一 `oryxos-boot` fat jar |
| SLF4J/Logback JSON、traceId | `application.yaml` + `TraceIdFilter.java` | POST/GET 日志包含 MDC `traceId` |
| Actuator + Micrometer/Prometheus | `oryxos-web/pom.xml`, `application.yaml` | `/actuator/health`, `/actuator/prometheus` |
| Spring MVC + API 响应和全局异常 | `SystemApiController`、`ApiResponse`、`GlobalExceptionHandler` | `/api/v1/health`, 500 不泄漏内部信息 |
| Swagger/OpenAPI | `oryxos-web/pom.xml` | `/v3/api-docs` & `/swagger-ui.html` |
| Google Java Format、Checkstyle、PMD | 父 POM + `config/checkstyle/checkstyle.xml` | `spotless:apply`, `verify` |
| SpotBugs | 父 POM | `verify` |
| P3C + Find Security Bugs | **未完整接入** | 选择兼容的 P3C 规则及插件，再次验证 |
| OWASP Dependency-Check | 父 POM 的 `security-check` profile | 执行扫描，保存报告 |
| `oryxos-init` skill | `.claude/skills/oryxos-init/SKILL.md` | 使用文档初始化，人工 Review |
| CI | `.github/workflows/ci.yml` | 推送私有测试仓库后实际运行 GitHub Actions |

## 已发现课件不一致

第16节 Profile 使用 `.oryxos/profiles/*.yaml`，第29/30节转为 `.oryxos/agents/<name>/AGENT.md`。正式实现应按老师最终基线统一，避免同时留下两种冲突的 Agent 真相源。

**不要把 Gate 1 的骨架当成第 3–7 周完整作业提交。**
