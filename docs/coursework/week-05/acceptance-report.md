# 第 5 周验收报告（C1 + C2：第 27、28 节）

Written for: 项目所有者与作业评阅者。**第 5 周尚未完成**（第 31、32 节还没做）；本报告覆盖第 27～30 节：第 1～10 节是 C1/C2，文末"C3 补充验收""C4 补充验收"是第 29、30 节。数字全部来自本机实际执行；Mock 验证与真实外部服务验证分开写。

## 1. 版本与分支

| 项 | 值 |
|---|---|
| 分支 | `feature/week-05-agent-lifecycle`（从标签 `week-04` 创建，基线 `4dc96af`；**未推送、未打标签**） |
| **C1** | `178411be6b29e9958369f9dd8dc187f6f8c68500` `feat(week05): verify agent execution end-to-end` |
| **C2** | `49777b7458fab89b7a56d5cbf95422b97afa0329` `test(week05): verify scheduler recovery and agent isolation` |
| 之后的提交 | 只有文档与证据（本报告、需求矩阵、限制、迭代记录、`evidence/`）和 `scripts/week05_live_check.py` 里多打印三行审计表行的改动；不改任何模块代码 |
| 验收对象 | C2 的内容（C1 之后另有一次 C1 的全量验证，见 `evidence/mvn-clean-verify-after-c1.txt`） |
| 时间 | 2026-10-30（本机 +11:00） |
| 环境 | Windows 10，JDK 21.0.2，Maven 3.9.16，Node v20.20.2，Python 3.14 |
| 工作区 | 验证前干净（除本轮新增文件）；开发在原目录的独立克隆 `oryxos-coursework` 里进行，没有触碰 `submission/week-04` 或 `week-04` 标签 |

## 2. 命令与结果

| 命令 | 结果 | 证据 |
|---|---|---|
| `npm ci`、`npm test`、`npm run build`（前端目录） | 通过；28 个前端测试；`check-build: ok` | — |
| `mvn -B -ntp spotless:apply` | BUILD SUCCESS，无改动 | — |
| `mvn -B -ntp clean verify`（C1 之后） | BUILD SUCCESS；517 个测试 | `evidence/mvn-clean-verify-after-c1.txt` |
| `mvn -B -ntp clean verify`（C2） | **BUILD SUCCESS**，九模块 + 父工程全部 SUCCESS，3 分 32 秒；没有 `-DskipTests`，没有关闭任何检查 | `evidence/mvn-clean-verify.txt` |
| 静态检查（同上日志） | Checkstyle 0 违规（10 次）；SpotBugs 0 个 BugInstance、Error size 0（9 个模块）；Spotless、PMD 通过 | 同上 |
| `python scripts/week05_live_check.py --jar …`（真实 JAR，独立 JVM，含杀进程重启） | **31/31 通过** | `evidence/live-check.txt` |
| `python scripts/week04_live_check.py --jar …`（第 4 周回归） | **77/77 通过** | `evidence/week04-live-check-regression.txt` |
| 无头 Chrome 驱动六个管理页（真实 JAR） | **27/27**（自动化，非人工） | `evidence/dashboard-headless-run.txt` |
| Admin、Swagger、Health、Prometheus | 200（含在 77 项与 31 项里） | 同上 |
| `git diff --check` | 见提交前检查（本报告所在提交） | — |

## 3. 测试数量

| 模块 | 测试 | 跳过 | 失败 | 第 4 周 | 增量 |
|---|---|---|---|---|---|
| oryxos-core | 33 | 0 | 0 | 33 | 0 |
| oryxos-provider | 42 | 0 | 0 | 37 | +5（`MockChatModelTest` +2，`ProviderRetryPolicyTest` 3） |
| oryxos-tool | 106 | **4** | 0 | 106 | 0 |
| oryxos-memory | 80 | 0 | 0 | 80 | 0 |
| oryxos-storage | 29 | 0 | 0 | 29 | 0 |
| oryxos-scheduler | 42 | 0 | 0 | 42 | 0 |
| oryxos-cli | 9 | 0 | 0 | 9 | 0 |
| oryxos-web | 64 | 0 | 0 | 56 | +8（`ScheduleOutcomeApiControllerTest`） |
| oryxos-boot（surefire 128 + failsafe 5） | 133 | 0 | 0 | 110 | +23 |
| **合计** | **538** | **4** | **0**（通过 534） | 502 | **+36** |

boot 的 +23：`MockAgentE2ETest` 7、`MockProviderFlowTest` 3、`SchedulerStabilityE2ETest` 6、`MultiAgentIsolationE2ETest` 5、`FullStackRestartRecoveryTest` 2（参数化：两种记忆后端）。

- **第 4 周的 502 个测试一个没少、没改**：本分支自 `week-04` 起没有删除任何测试文件，现有测试文件只在 `MockChatModelTest` 末尾追加了 2 个用例（`git diff --diff-filter=D` 与对既有测试的删行检查见提交前检查）。
- **4 个跳过**仍是 `PathSecurityTest` 的符号链接测试（Windows 账号没有创建符号链接的权限），如实记录，没有算作通过。
- 前端另有 28 个 vitest，不计入上表。

## 4. 两次模型调用 + 一次工具调用的实际审计证据

来自真实 JAR（`live-check.txt`），天气请求 `api:w1:agent-web`：

```
llm_calls       : [(1, 'mock', 'm', 1, None), (2, 'mock', 'm', 1, None)]
tool_invocations: [(1, 'http_get', 1, None, None)]
weather fixture requests: ['/weather']
```

`MockAgentE2ETest` 里同一场景三方对账：假模型服务器收到 **2** 个 HTTP 请求（第一个带 `tools=[http_get]`，第二个以 `tool` 消息结尾）；天气 Fixture 收到 **1** 个请求；SQLite `llm_calls` 2 行 `provider=fake-openai success=1`，`tool_invocations` 1 行 `http_get success=1`；会话步骤 `USER, ASSISTANT, TOOL, ASSISTANT`。

## 5. Scheduler 重启恢复

- 集成测试（`FullStackRestartRecoveryTest`，两种记忆后端）：重启前后 `sessions`、`llm_calls`、`tool_invocations`、`scheduled_tasks`、`task_executions`、`memory_entries` 六张表、记忆文件、记忆接口读数**逐行相同**；停用开关和 `run_count`、`last_status` 保留；重启后继续使用，旧审计行不变，新行追加。
- 真实 JAR（`week05_live_check.py`）：停止 JVM 后在同样文件上新启动一个 JVM，六张表逐表比较全部相同（9 项检查）。

## 6. 多 Agent 隔离

通过：工具白名单、Sandbox、Session、定时任务失败隔离（`MultiAgentIsolationE2ETest` 5 项，加真实 JAR 7 项）。**未通过/未实现：长期 Memory 仍是全局共享**，已用特征化测试固定"现状"，留给第 30 节。详见需求矩阵 28.7、28.8。

## 7. 对"技术成功 ≠ 业务成功"的处理

没有改 `TaskExecution`、数据库或既有 API。新增只读端点 `GET /api/v1/schedules/{id}/executions/{executionId}/outcome`：一个关键工具被沙箱拒绝的定时运行，`lastStatus` 仍是 `SUCCESS`（与 B3 一致），但该端点返回 `outcome=COMPLETED_WITH_TOOL_ISSUES` 并说明"不是已确认的业务成功"。报告和后续界面应以它为准，不要把这种运行写成"任务执行成功"。

## 8. 哪些真实外部集成尚未测试

真实 LLM（DeepSeek 等）、真实 MCP 服务器、真实 Webhook、真实 Mem0、真实天气 API：**全部没有测试**。本周的"模型"是本地假服务器和进程内 Mock；"天气"是 127.0.0.1 上的 Fixture（沙箱因此需要在 Profile 和运维上限都开启 `allow_private_networks`，这是测试配置，不代表对真实公网天气 API 的验证）。详见 [known-limitations.md](known-limitations.md)。

## 9. 进入第 29 节前需要解决的问题

见 [known-limitations.md](known-limitations.md) 末节"进入第 29/30 节前"。最关键的几条：Agent 定义的单一事实来源（`AGENT.md` 与 `profiles/*.yaml` 不能双写）、可变的 Profile 目录、沙箱缓存驱逐、per-agent 记忆、工具审计的执行 ID、管理 API 在没有认证时的边界。

## 10. 状态

第 27 节（C1）：**Mock 验证通过**，一项（真实模型测试）未实现/未验证。第 28 节（C2）：**Mock 验证通过**，per-agent memory 隔离**尚未实现**。不推送、不发布第 5 周标签。

---

# C3 补充验收（第 29 节：`AGENT.md` 插件化）

| 项 | 结果 |
|---|---|
| `mvn -B -ntp clean verify`（C3 代码） | **BUILD SUCCESS**，九模块全部 SUCCESS；Checkstyle 0 违规、SpotBugs 0 BugInstance、PMD、Spotless 通过；`evidence/mvn-clean-verify-c3.txt` |
| 测试 | **616 个**（C2 为 538，新增 78），**611 通过、5 跳过、0 失败**。5 个跳过 = 既有 4 个符号链接测试 + 新增的 1 个符号链接用例（`AgentLoaderTest`，该 Windows 账号无创建符号链接的权限） |
| 新增测试 | provider：`AgentLoaderTest` 23、`DeriveProfileTest` 3、`AgentScanRegisterTest` 13、`ProgressiveDisclosureTest` 6、`ProfileRegistryRuntimeTest` 7；scheduler：`AgentSchedulerRegisterTest` 10；tool：`SandboxPolicyInvalidationTest` 7；boot：`AgentPluginIntegrationTest` 9 |
| 真实 JAR | `scripts/week05_agents_live_check.py --stage c3`：**22/22**（`evidence/live-check-c3.txt`）：AGENT.md 目录启动即注册、REST 与 CLI 都能看到、同名冲突保留旧 YAML、坏目录不注册但有日志、计划入库、Mock 运行并审计、沙箱放行/拒绝、脚本未被执行、Health/Swagger/Admin 200、重启后 `scheduled_tasks`/`llm_calls`/`tool_invocations` 逐行相同 |
| 回归中发现并修正的问题 | 把调度器的定义表改成并发映射后，既有 `AgentSchedulerTest`、`MultiAgentSchedulerIsolationTest` 因注册顺序变化失败；改为"写时复制的有序快照"（读无锁、写在 monitor 下、保持 Profile 顺序），既有测试未改动即通过 |
| 未做 | 运行中新增目录的 JAR 级验证（文件监听与 REST 是第 30 节）；真实 LLM / MCP / Webhook / Mem0 仍未验证 |

---

# C4 补充验收（第 30 节：动态管理 Agent）与最终结果

| 项 | 结果 |
|---|---|
| `npm ci`、`npm test`、`npm run build` | 通过；**38** 个前端测试（原 28 不变 + 新增 10）；`check-build: ok` |
| `mvn -B -ntp spotless:apply`、`mvn -B -ntp clean verify` | **BUILD SUCCESS**，九模块 + 父工程全部 SUCCESS；Checkstyle 0 违规、SpotBugs 0 BugInstance（9 个模块）、PMD、Spotless 通过；`evidence/mvn-clean-verify-c4.txt` |
| 测试数量 | **728 个：722 通过、6 跳过、0 失败**（C3 为 616，新增 112；第 4 周为 502）。分模块见 `evidence/test-summary.md` |
| 6 个跳过 | 既有 4 个符号链接测试 + 2 个新增的文件符号链接用例（`AgentLoaderTest`、`AgentWorkspaceTest`）：该 Windows 账号无创建符号链接的权限，如实记为跳过。目录 Junction 逃逸用例真实运行 |
| 真实 JAR：第 29/30 节 | `scripts/week05_agents_live_check.py --stage all`：**96/96**（C3 22 + C4 74），`evidence/live-check-c3-c4.txt`。覆盖：AGENT.md 目录注册、REST 创建/更新/归档、重启恢复、调度动态更新、Sandbox 权限收紧、每 Agent 记忆隔离、`execution_id` 审计归属、会话并发、文件监听、工作区 API、跨站/重绑定拒绝、局域网地址不可达、**无头 Chromium 对管理台的真实交互** |
| 真实 JAR：回归 | 第 5 周 C1/C2 检查 **31/31**（`evidence/live-check.txt`）；第 4 周检查 **77/77**（`evidence/week04-live-check-regression.txt`，只把"迁移版本 1..3"改为"1..4"） |
| 新增/修改的测试 | provider：`AgentLifecycleServiceTest` 14、`AgentUpdateRollbackTest` 7、`AgentWorkspaceTest` 13、`GenerateTest` 11、`WorkspaceWatcherTest` 10；memory：`PerAgentMemoryIsolationTest` 10；core：`AgentSessionConcurrencyTest` 4；storage：`SqliteMigrationTest` +2；web：`AgentManagementApiControllerTest` 16、`WorkspaceApiControllerTest` 6、`ScheduleOutcomeApiControllerTest` +3；boot：`SchedulerExecutionCorrelationTest` 5、`AdminAgentFlowTest` 11；前端 `agents.test.js` 10 |
| 质量门禁过程中发现并修复的问题 | ① Junction 目录下的文件出现在工作区树里（`Files.walk` 会进入 Junction）→ 逐级拒绝重解析点；② `%` 现在在路径里被拒绝（防双重编码）；③ 管理台保存后整页回到"Loading…"丢失标签状态 → 静默刷新；④ SpotBugs/PMD 共 8 处（空 catch、`getParent()` 可能为 null、格式串换行、不一致同步、`write` 被当作可变接口 → 改名 `save`、控制器空请求体）全部改代码修复，没有关闭任何检查 |
| 为新迁移而改动的既有测试 | `SqliteMigrationTest`（最新版本 3→4，原"坏的第 4 个迁移"改为"坏的第 5 个"）、`MigrationStartupTest`（3→4）、`ScheduleOutcomeApiControllerTest`（未改动旧断言，另增 3 个）。**没有删除任何测试，没有放宽任何断言** |
| 变异检查 | 让会话锁失效后 `AgentSessionConcurrencyTest` 4 个里 2 个失败，已还原 |

## 第 5 周 C3 + C4 的结论

- 第 29 节（C3）：**Mock 验证通过**。
- 第 30 节（C4）：**Mock 验证通过**；真实 LLM 生成、MCP、Webhook、Mem0 **未验证**；管理台**没有人工浏览器验收**；仍然**没有认证**。
- 不推送、不打 `week-05` 标签。

## 第 31 节三个 Demo 的前置条件

| 前置 | 状态 |
|---|---|
| 用目录声明新 Agent，不改 Java、不重新打包 | 就绪（C3） |
| 通过 REST/管理台创建、修改、运行、归档 Agent，改动立即生效且失败可回滚 | 就绪（C4） |
| 每个 Agent 独立长期记忆 | 就绪（仅 `AGENT.md` Agent；Mem0 不支持） |
| 定时任务的结果有精确的审计归属，技术成功与业务结果分开 | 就绪（`execution_id`） |
| 无 Key 可复现的运行（假模型/Mock） | 就绪 |
| 用真实模型跑 Demo | **未就绪**：没有验证过任何真实 LLM；需要 Key 和一次真实运行，并核对第 31 节要求（Demo 是两个还是三个仍待官方说明） |

---

# C5 补充验收（第 31 节：三个业务 Agent Demo）与最终结果

| 项 | 结果 |
|---|---|
| `npm ci`、`npm test`、`npm run build` | 通过；**42** 个前端测试（C4 为 38，新增 4）；`check-build: ok` |
| `mvn -B -ntp spotless:apply`、`mvn -B -ntp clean verify` | **BUILD SUCCESS**，九模块 + 父工程全部 SUCCESS；Checkstyle 0 违规、SpotBugs 0 BugInstance（9 个模块）、PMD、Spotless 通过；`evidence/c5/mvn-clean-verify-c5.txt` |
| 测试数量 | **786 个：780 通过、6 跳过、0 失败**（C4 为 728，新增 58；第 4 周为 502）。6 个跳过与 C4 相同（4 个既有符号链接 + 2 个新增文件符号链接用例）；本机有 Python，所以依赖 Python 的测试都实际运行了。分模块见 `evidence/test-summary.md` |
| 新增测试 | boot：`ExampleAgentsLoadTest` 4、`WeatherDailyAgentTest` 8、`TechDigestAgentTest` 9、`GitHubTrendingScriptTest` 12、`GitHubDailyAgentTest` 8、`AgentDemoSchedulerIT` 3、`AgentDemoAuditIT` 3、`AgentDemoIsolationTest` 5、`AgentDemoRestartIT` 1；scheduler：`ScheduleCrossDayTest` 4；web：`ScheduleOutcomeApiControllerTest` +1；前端 `agents.test.js` +4。共享夹具 `DemoSupport`、`AbstractAgentDemoIT` |
| 真实 JAR：三个 Demo | `scripts/week05_c5_live_check.py`：**64/64**（`evidence/c5/live-check-c5.txt`）：已安装示例注册、REST/CLI 可见、手动运行、**真实 Cron 各触发一次**、CLI `chat`、记忆偏好改变排序、失败不算业务成功、沙箱拒绝其它参数、重启后五张表和文件字节相同、Playwright 无头 Chromium 操作管理台 |
| 真实 JAR：回归 | 第 29/30 节 **96/96**（`evidence/live-check-c3-c4.txt`）；第 27/28 节 **31/31**（`evidence/live-check.txt`）；第 4 周 **77/77**（`evidence/week04-live-check-regression.txt`） |
| Tier B 真实外部服务 | `evidence/c5/external-tier-b.txt`：**已验证**：Open-Meteo 直接 GET，以及经 JAR 真实 `http_get` 的天气 Agent；Hacker News Search（Algolia）；GitHub Search API（经真实脚本，未认证）。**未验证**：真实 LLM（没有 Key），真实 Webhook（没有授权目标） |
| 过程中发现并修复的问题 | ① 脚本在 Windows 管道里用 ANSI 代码页输出，中文/省略号会乱码 → 脚本 stdout 强制 UTF-8；② 描述里的控制字符替换后出现连续空格 → 折叠空白；③ `AgentLoader` 把 `scripts/__pycache__/*.pyc` 当作 Agent 文件列出 → 过滤；④ `ExecutionOutcome` 新增的列表字段触发 SpotBugs → 防御性拷贝；⑤ 科技日报的脚本化模型把提示词里"优先关注 AI 和芯片"的**示例文字**当成了记忆 → 按 MemoryService 真正的标题 `## Long-term memory` 取记忆段 |
| 对既有代码的改动 | 仅 `AgentLoader`（忽略 `__pycache__`）、`ScheduleOutcomeApiController`（outcome 增加 `calls` 明细）、管理台 `RunsPanel`；`SchedulerFixtures`（测试夹具）让执行 ID 透传；既有测试没有删除或放宽 |

## C5 结论

- 三个 Demo 在 Tier A（Mock/夹具）下完整通过；Tier B 中三个公开只读接口真实访问成功；**真实 LLM 与真实 Webhook 未验证**；**真实连续 48 小时运行未发生**；管理台无人工浏览器验收；仍然没有认证。
- 不推送、不打 `week-05` 标签、不创建 `v0.1.0`，发布留给 C6。

## 发布阻塞项（交给 C6）

1. 没有真实 LLM 的验证（需要 Key 和一次真实运行；Demo 是两个还是三个仍待官方说明）。
2. 第 5 周提交分支 `submission/week-05`、标签 `week-05` 尚未创建；课程平台提交未做。
3. 管理台只有自动化验证；管理 API 无认证，只适合本机。
4. 文档中的"待核对"项：老师的评分细则、提交页面的具体要求。
