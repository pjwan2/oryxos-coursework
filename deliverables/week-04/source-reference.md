# 源码参考

## Git

| 项 | 值 |
|---|---|
| 仓库 | `oryxos-coursework`（GitHub 地址见课程平台提交说明与验收报告） |
| 提交分支 | `submission/week-04` |
| 阶段提交 | A `9c07dc0` → B1 `d57fcd2`（Memory）→ B2 `67e7364`（Sandbox）→ B3 `4c1e3cd`（Scheduler）→ B4 `e6c76c0`（REST + Vue 管理台）→ 周交付 `f31fd9f`…`6c1e643` → 仓库整理 `3c9bb86`、`c8b8513` → 本次发布整理（见验收报告中的精确 SHA） |
| 验收所用提交 | 见 `acceptance-report.md` 第 1 节（在该提交的干净克隆上重新执行了完整流程） |
| 之后的提交 | 只增加验收证据与提交包文件，不含模块代码改动；核对：`git diff --stat <验收提交> HEAD -- oryxos-core oryxos-provider oryxos-tool oryxos-memory oryxos-storage oryxos-scheduler oryxos-cli oryxos-web oryxos-boot` 应为空 |

源码 ZIP（`oryxos-week-04-source.zip`）由 `git archive` 生成，只含 Git 跟踪的文件，没有数据库、日志、`.env`、`node_modules`、构建产物；它是 GitHub 仓库的备份，不是主要交付方式。

## 关键源码位置（按课）

### 第 21、22 课：Memory

| 作用 | 路径 |
|---|---|
| 契约 | `oryxos-core/src/main/java/io/oryxos/core/memory/LongTermMemoryStore.java`、`MemoryScope.java`、`MemoryEntry.java`、`MemoryText.java` |
| 三个后端 | `oryxos-memory/src/main/java/io/oryxos/memory/MarkdownMemoryStore.java`、`SqliteMemoryStore.java`、`Mem0MemoryStore.java` |
| 服务、工具、提示词 | `MemoryService.java`、`SaveMemoryTool.java`、`RecallMemoryTool.java`、`MemoryContextLoader.java`、`BootstrapContextLoader.java`（同目录） |
| 后端选择与配置 | `oryxos-boot/src/main/java/io/oryxos/MemoryConfiguration.java`、`oryxos-boot/src/main/resources/application.yaml`（`oryxos.memory.*`） |
| 表结构 | `oryxos-storage/src/main/resources/db/schema.sql`（`memory_entries`） |
| 测试 | `oryxos-memory/src/test/java/io/oryxos/memory/`（`MemoryStoreContractTest` 及三个后端测试）、`oryxos-boot/src/test/java/io/oryxos/MemoryPromptIntegrationTest.java`、`SqliteMemoryPromptIntegrationTest.java`、`MemoryRestartTest.java` |

### 第 23、24 课：Sandbox

| 作用 | 路径 |
|---|---|
| 契约与声明解析 | `oryxos-core/src/main/java/io/oryxos/core/sandbox/Sandbox.java`、`SandboxSpec.java` |
| 实现 | `oryxos-tool/src/main/java/io/oryxos/tool/ProfileSandbox.java`、`AllowListSandbox.java`、`SandboxPolicy.java`、`CommandRule.java` |
| 使用沙箱的工具 | `FileReadTool.java`、`FileWriteTool.java`、`HttpGetTool.java`、`ShellTool.java`、`notify/NotifyTool.java`（同目录） |
| 执行与审计 | `RegistryToolExecutor.java`、`AuditedToolExecutor.java`；`oryxos-core/src/main/java/io/oryxos/core/agent/SafeErrorText.java` |
| 配置 | `oryxos-boot/src/main/java/io/oryxos/ToolConfiguration.java`；示例 `examples/profiles/sandbox-example.yaml` |
| 测试 | `oryxos-tool/src/test/java/io/oryxos/tool/`（`PathSecurityTest`、`UriSecurityTest`、`ShellSecurityTest`、`ProfileSandboxTest`、`SandboxEnforcementTest`…）、`oryxos-boot/src/test/java/io/oryxos/SandboxIntegrationTest.java`、`SandboxStartupValidationTest.java` |

### 第 25 课：定时任务

| 作用 | 路径 |
|---|---|
| 契约 | `oryxos-core/src/main/java/io/oryxos/core/schedule/`（`ScheduleService`、`ScheduledTaskStore`、`TaskExecution`…） |
| 实现 | `oryxos-scheduler/src/main/java/io/oryxos/scheduler/AgentScheduler.java`、`ScheduleConfigParser.java` |
| 持久化与迁移 | `oryxos-storage/src/main/java/io/oryxos/storage/JdbcScheduledTaskStore.java`、`oryxos-storage/src/main/java/io/oryxos/storage/migration/` |
| 装配 | `oryxos-boot/src/main/java/io/oryxos/SchedulerConfiguration.java` |
| REST | `oryxos-web/src/main/java/io/oryxos/web/api/ScheduleApiController.java` |
| 示例 | `examples/profiles/scheduled-example.yaml` |
| 测试 | `oryxos-scheduler/src/test/java/io/oryxos/scheduler/`、`oryxos-storage/src/test/java/io/oryxos/storage/`、`oryxos-boot/src/test/java/io/oryxos/ScheduledTaskE2ETest.java`、`RestartRecoveryTest.java`、`MigrationStartupTest.java` |

### 第 26 课：Web Service 与管理台

| 作用 | 路径 |
|---|---|
| 六类控制器 | `oryxos-web/src/main/java/io/oryxos/web/api/{Session,Agent,Profile,Memory,Tool,System}ApiController.java` |
| 单一执行入口 | `oryxos-web/src/main/java/io/oryxos/web/api/AgentInvoker.java` |
| 统一响应与异常、本地限制 | `ApiResponse.java`、`GlobalExceptionHandler.java`、`LocalAccess.java`；`oryxos-web/src/main/java/io/oryxos/web/trace/TraceIdFilter.java` |
| Provider 失败分类 | `oryxos-provider/src/main/java/io/oryxos/provider/ModelFailures.java` |
| 管理台托管 | `AdminUiConfiguration.java`、`AdminIndexController.java` |
| Vue 源码 | `oryxos-web/src/main/frontend/src/`（`views/`、`components/`、`api.js`、`scheduler.js`） |
| 构建检查 | `oryxos-web/src/main/frontend/scripts/check-build.mjs`；`oryxos-web/pom.xml`（enforcer） |
| 测试 | `oryxos-web/src/test/java/io/oryxos/web/api/`；`oryxos-boot/src/test/java/io/oryxos/RestApiIntegrationTest.java`、`AdminUiTest.java`、`WebSmokeIT.java`；`oryxos-web/src/main/frontend/tests/` |

### 架构守卫

`oryxos-boot/src/test/java/io/oryxos/ArchitectureRulesTest.java`（规则说明：`docs/architecture/module-boundaries.md`）。

## 构建与复现

| 目的 | 命令 |
|---|---|
| 完整构建（含前端） | `scripts/build-all.sh`，或 README 的快速开始 |
| 只测某个模块及其依赖 | `mvn -B -ntp -pl oryxos-memory -am test` |
| 真实 JAR 实机检查 | `python scripts/week04_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar` |
| 测试汇总 | `python scripts/test_summary.py`（`mvn verify` 之后） |
| 仓库安全审计（含 Git 历史） | `python scripts/security_audit.py` |
| 重新生成提交包 | `python scripts/package_week04.py` |

静态检查（Spotless、Checkstyle、PMD、SpotBugs）在 `verify` 阶段运行，任何一项失败都会让构建失败；没有关闭或加排除规则。

## 配置要点（没有任何密钥进入仓库）

| 设置 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | 8080 | 服务只绑 `127.0.0.1` |
| `ORYXOS_JDBC_URL` | `jdbc:sqlite:./oryxos-dev.db` | SQLite 文件（被 `.gitignore` 忽略） |
| `ORYXOS_PROFILE_DIR` | `.oryxos/profiles` | Agent 定义 |
| `ORYXOS_MEMORY_BACKEND` | `markdown` | `markdown` / `sqlite` / `mem0` |
| `ORYXOS_SCHEDULER_ENABLED` | `true` | 是否注册 cron 触发 |
| `DEEPSEEK_API_KEY`、`MEM0_API_KEY` | 空 | 只从环境变量读取；本次验收没有使用 |

模板：`config/env.example`。
