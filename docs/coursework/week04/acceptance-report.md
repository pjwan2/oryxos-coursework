# 第 4 周验收报告

Written for: 作业评阅者与你自己（核对"哪些真的验证过"）。所有数字来自本机实际执行的命令；没有执行过的验证不写成通过。

## 1. 被验收的版本

| 项 | 值 |
|---|---|
| 仓库 | `OryxOS_Gate2C_Session_Audit_v1_5`（唯一主工程，分支 `feature/spring-ai-react-integration`） |
| **验收提交** | `f31fd9f50202526defbf8942d25d01fe4acfda18`（"Week 4 delivery prep…"） |
| 验收方式 | 对该提交做一次**全新的 `git clone`**（没有 `node_modules`、没有 `static/admin`、没有任何 `target`，见 `evidence/clean-checkout/00-environment.txt`），在克隆里按 README 的步骤执行 |
| 之后的提交 | 只增加本报告、证据文件和提交包，没有改动任何模块的代码（核对命令见 `deliverables/week04/source-reference.md`） |
| 阶段提交 | A `9c07dc0`、B1 `d57fcd2`、B2 `67e7364`、B3 `4c1e3cd`、B4 `e6c76c0`（均与真实 Git 记录一致） |
| 环境 | Windows 10 Pro，JDK 21.0.2，Maven 3.9.16，Node v20.20.2，npm 10.8.2，Python 3.14 |

**"干净环境"的含义和局限**：这是同一台机器上的干净克隆，不是新虚拟机或容器；Maven 本地仓库（`~/.m2`）和 npm 缓存是已有的。它证明"仓库内容本身足以按 README 构建和启动"，不证明"在完全没有缓存、没有网络的机器上能构建"。

## 2. 执行的命令与结果

全部在克隆目录里执行，原始输出在 `evidence/clean-checkout/`。

| # | 命令 | 结果 | 证据文件 |
|---|---|---|---|
| 1 | `node -v`、`npm -v`、`java -version`、`mvn -v` | Node 已存在（v20.20.2），无需安装，未改任何全局环境 | `00-environment.txt` |
| 2 | `cd oryxos-web/src/main/frontend && npm ci` | 130 个包，exit 0 | `01-npm-ci.txt` |
| 3 | `npm test`（vitest） | 3 个文件、**28 个测试全部通过** | `02-npm-test.txt` |
| 4 | `npm run build`（vite build + `check-build.mjs`） | 成功；`check-build: ok (16 files, 3 assets)`（无外部 URL、无 source map、无疑似凭证） | `03-npm-build.txt` |
| 5 | `mvn -B -ntp spotless:apply` | BUILD SUCCESS；执行后 `git status` **为空**（格式化没有改动任何文件） | `04-mvn-spotless-apply.txt`、`04b-git-status-after-spotless.txt` |
| 6 | `mvn -B -ntp clean verify` | **BUILD SUCCESS**，九个模块全部 SUCCESS，用时 2 分 38 秒，**没有使用 `-DskipTests`**，没有关闭任何检查 | `05-mvn-clean-verify.log` |
| 7 | `python scripts/test_summary.py` | 见第 3 节 | `evidence/test-summary.md` |
| 8 | 检查 fat JAR 内容 | JAR 85.7 MB；内嵌的 `oryxos-web-0.1.0-SNAPSHOT.jar` 含 `static/admin/index.html` 和全部资源 | `06-jar.txt`；`WebSmokeIT` 也在构建中自动检查 |
| 9 | `python scripts/week04_live_check.py --jar …`（真实 JAR，临时目录） | **77/77 检查通过** | `evidence/live-check.txt` |
| 10 | `java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar`（**不带任何参数**，工作目录=仓库根） | 启动成功；health 200；`/admin/`、`/admin/scheduler`、Swagger UI 200；Prometheus 有指标；监听 `127.0.0.1:8080`；用默认 Markdown 记忆写入并读到 `MEMORY.md` | `08-readme-default-start.txt` |

静态检查（`verify` 阶段，`evidence/static-analysis-summary.txt`）：Checkstyle 0 违规（父工程 + 9 个模块共 10 次运行）、SpotBugs 0 个 BugInstance 且 Error size 0（9 个模块）、Spotless check 全部模块通过、PMD `check` 目标在 `failOnViolation=true` 下通过（PMD 不打印违规计数，构建成功即无违规）。构建期间**没有**关闭、放宽或为它们加排除规则。

## 3. 测试数量

来源：克隆上 `mvn clean verify` 生成的 surefire / failsafe XML，由 `scripts/test_summary.py` 汇总（完整的逐类表在 `evidence/test-summary.md`）。

| 模块 | 测试 | 跳过 | 失败 | B3 基线 | 增量 |
|---|---|---|---|---|---|
| oryxos-core | 33 | 0 | 0 | 33 | 0 |
| oryxos-provider | 37 | 0 | 0 | 31 | +6 |
| oryxos-tool | 106 | **4** | 0 | 106 | 0 |
| oryxos-storage | 29 | 0 | 0 | 29 | 0 |
| oryxos-memory | 80 | 0 | 0 | 80 | 0 |
| oryxos-scheduler | 42 | 0 | 0 | 42 | 0 |
| oryxos-cli | 9 | 0 | 0 | 9 | 0 |
| oryxos-web | 56 | 0 | 0 | 17 | +39 |
| oryxos-boot（surefire 105 + failsafe 5） | 110 | 0 | 0 | 80 | +30 |
| **合计** | **502** | **4** | **0** | 427 | **+75** |

- 通过 498、跳过 4、失败 0。
- **既有 427 个测试无回退**：自 B3（`4c1e3cd`）以来没有删除任何测试文件；3 个既有测试文件被修改，**全是新增行（63 行插入、0 行删除）**：两个测试替身补了新接口方法（`AgentServiceTest`、`SchedulerFixtures`），`ProviderServiceTest` 新增一个用例。没有改动任何既有断言。核对命令：`git diff --diff-filter=D --name-only 4c1e3cd HEAD -- '*src/test*'`（空）、`git diff --diff-filter=M 4c1e3cd HEAD -- '*src/test*' | grep '^-'`（空）。
- 增量 75 = B4 的 67（`SessionApiControllerTest` 22、`AgentApiControllerTest` 6、`QueryApiControllersTest` 11、`ModelFailuresTest` 5、`ProviderServiceTest` +1、`RestApiIntegrationTest` 11、`AdminUiTest` 6、`WebSmokeIT` 5）+ 本轮 8（`ArchitectureRulesTest`）。
- 前端另有 vitest 28 个测试，不计入上表。
- **4 个跳过**：`PathSecurityTest` 里的符号链接测试（本机 Windows 账号没有创建符号链接的权限）。如实记录为跳过；junction 版本的同一路径测试执行并通过。见 [known-limitations.md](known-limitations.md) B 节。

## 4. 对 Phase 7 清单的逐项核对

| 要求 | 结果 | 证据 |
|---|---|---|
| Maven 九模块全部成功 | **通过** | `05-mvn-clean-verify.log` Reactor Summary |
| 既有 427 个测试无回退，新测试全部计入 | **通过**（502） | 第 3 节 |
| 4 个符号链接测试继续如实记录 | **如实记录为跳过** | `test-summary.md` 中 `PathSecurityTest` 18 / 跳过 4 |
| Memory Markdown / SQLite 正常 | **通过** | `live-check.txt` 两个后端各 11 项；契约测试三后端；`MemoryRestartTest` |
| Sandbox 拒绝及审计正常 | **通过** | `live-check.txt` Sandbox 节：7 次拒绝与 `tool_invocations` 行一一对应，越界文件未创建；`SandboxIntegrationTest` |
| Scheduler 执行、停用、重启恢复正常 | **通过** | `live-check.txt` Scheduler 节（杀进程重启后状态、计数、历史不变）；`ScheduledTaskE2ETest`、`RestartRecoveryTest`、`WebSmokeIT` |
| REST API 返回正确状态 | **通过** | `live-check.txt` Web 节：200/201/400/403/404/500；`RestApiIntegrationTest`；503/504 见下 |
| Prometheus / Swagger 正常 | **通过** | `live-check.txt`；`08-readme-default-start.txt` |
| `/admin/` 能访问，子路由刷新不 404，`/api` 不受影响 | **通过** | `AdminUiTest`、`WebSmokeIT`、`live-check.txt` |
| Vue 页面显示真实数据 | **通过（自动化）** | `evidence/b4-live-run/`：无头 Chrome 在真实 JAR 上 27 项 + 重启后 5 项检查，页面数据与 API、SQLite 逐项一致，13 张截图；该运行基于 `e6c76c0`，此后后端与前端代码未变（本轮只加测试、脚本、文档）。**不是人工验收** |
| 可执行 JAR 包含前端资源 | **通过** | `06-jar.txt`；`WebSmokeIT.theFatJarContainsTheVueBuild` |
| 干净环境按 README 启动 | **通过（同机干净克隆）** | `08-readme-default-start.txt`；局限见第 1 节 |
| 503 / 504 | **仅合成异常验证** | `ModelFailuresTest`、`ProviderServiceTest`、控制器切片测试；没有真实 Provider 故障的运行证据 |

## 5. 架构审计的结果

`ArchitectureRulesTest`（8 个测试）在构建中检查：九个模块、允许的依赖图、core 只导入 JDK、数据库 API 只在 storage（白名单：`SqliteMemoryStore`、`MemoryConfiguration`）、web 不触达执行与基础设施、provider/scheduler 不能直接执行工具或循环、只有一个 ToolRegistry / 会话存储 / 工具审计写入者、每个工具执行器都包着审计。

- 审计**没有发现需要改代码的违规**，所以没有重构、没有重命名包、没有改公共接口。
- 做过一次变异检查：往 web 的一个类里加 `import java.sql.Connection`，守卫测试按预期失败并指出文件名，随后还原（`git diff` 为空）。
- 两处如实记录的结构偏差和一个历史遗留的第二个 `AgentService` Bean 见 [module-boundaries.md](../../architecture/module-boundaries.md)、[design-decisions.md](../../architecture/design-decisions.md) D-08。

## 6. 没有验证的东西（摘要）

完整列表见 [known-limitations.md](known-limitations.md)。最重要的：

1. 真实 LLM、真实 MCP、真实 Webhook、真实 Mem0：**从未运行**，全部使用 Mock / 假服务。
2. 4 个符号链接测试：**跳过**。
3. 无认证；Sandbox 是应用层策略，不是 OS 隔离；DNS 重绑定未消除。
4. 管理台：**没有人工浏览器验收**。
5. 503/504：只用合成异常验证。
6. 老师的评分标准与提交方式：**等待核对**。

## 7. 本次整理没有做的事

没有推送任何远程；没有登录课程平台；没有提交作业；没有移动或删除任何历史目录（只给出了待你批准的归档方案，见 `docs/architecture/workspace-layout.md`）；没有 `git reset --hard`、`git clean` 或强制操作。
