# 第 5 周需求对照矩阵（C1：第 27 节，C2：第 28 节）

每一项：**Requirement → Source → Test → Result → Evidence**。状态只用四种：

- **Mock 验证通过**：用本地确定性模型/假服务器和真实的工具、沙箱、记忆、SQLite、REST 验证；
- **真实外部服务验证通过**：（本周没有任何一项）；
- **未验证**：写了测试条件但缺少外部依赖，没有运行；
- **尚未实现**：代码里没有。

## 要求来源

第 27、28 节的详细讲义**不在仓库里**。本矩阵的"要求"来自本周任务书对 C1、C2 的描述和讲义整理包（`OryxOS_When_Homework_Implementation_v1/docs/EXECUTION_ROADMAP.md`，是整理稿，不是老师的正式评分标准）。它们是**技术验收依据**，不是已确认的作业评分细则；老师的评分要求、提交页面的具体题目仍**等待核对**（见 [../week-04/requirements-matrix.md](../week-04/requirements-matrix.md) 末尾的提交规则）。

证据目录：[evidence/](evidence/README.md)。测试类在 `oryxos-boot/src/test/java/io/oryxos/`（表中简称 boot）、`oryxos-web/src/test/...`（web）、`oryxos-provider/src/test/...`（provider）。

## 测试方法（为什么这些结果可信）

- **假模型服务器**（`LocalTestServers.FakeModelServer`）在 127.0.0.1 上讲 OpenAI 聊天协议，只负责"脚本化模型的回答"。**真实的** Spring AI 客户端、`ProviderService`、ReAct 循环、`ToolExecutor`、沙箱、记忆文件、SQLite 审计、REST 全部照常运行；测试**从不**直接往 `llm_calls`/`tool_invocations` 插行。
- 每个数字三方对账：假模型服务器自己收到的 HTTP 请求数、天气 Fixture 服务器收到的请求数、SQLite 表里的行数。
- 变异检查：把 `internalToolExecutionEnabled(false)` 改成 `true`，`MockAgentE2ETest` 7 个里 6 个失败（已还原）。证明"无重复工具执行"这条断言抓得住问题。
- 全部用临时目录和临时 SQLite，不碰开发数据库。

## C1 — 第 27 节：主流程集成

| # | Requirement | Source | Test | Result | Evidence |
|---|---|---|---|---|---|
| 27.1 | 确定性 Mock Provider：无 Key、无公网，驱动真实 ReAct | `MockChatModel`（provider，已有，Spring AI `ChatModel` 实现）；`LocalTestServers.FakeModelServer`（boot 测试辅助） | provider `MockChatModelTest`（6，其中新增 2：同一 Prompt 同一答案；工具结果之后只回文字、不再请求工具）；boot `MockProviderFlowTest`、`MockAgentE2ETest` | **Mock 验证通过** | `evidence/test-summary.md` |
| 27.2 | 场景一：天气。模型请求 `http_get` → 本地 Fixture → 第二次模型生成答复；恰好 2 次模型调用、1 次工具调用；`MEMORY.md` 不变 | `ReActLoop`、`SpringAiModelGateway`、`HttpGetTool`、`AuditedToolExecutor` | boot `MockAgentE2ETest.theCountsAgreeBetweenTheModelServerTheFixtureAndSqlite`（模型服务器 2 个请求、Fixture 1 次、`llm_calls` 2 行成功、`tool_invocations` 1 行 `http_get` 成功、会话步骤 USER/ASSISTANT/TOOL/ASSISTANT、内存文件不变）；`MockProviderFlowTest.weatherThroughTheMockProvider…`（进程内 Mock，同样对账） | **Mock 验证通过** | 同上；真实 JAR：`evidence/live-check.txt`（`llm_calls: [(1,'mock',…),(2,'mock',…)]`，`tool_invocations: [(1,'http_get',1,…)]`，Fixture 请求 1 次） |
| 27.3 | 场景二：长期记忆。"记住：我喜欢喝美式咖啡"→ `save_memory` → 真实 `MemoryService` 写入；2 次模型调用、1 次工具调用；新会话能读到 | `SaveMemoryTool`、`MemoryService`、`MarkdownMemoryStore`、`MemoryContextLoader` | boot `MockAgentE2ETest.rememberingAFactWritesRealMemoryAndANewSessionSeesIt`（CORE 写入 `MEMORY.md` 的"核心记忆"下；**新会话发给模型的 system prompt 里已含该事实**；REST `/memory` 一致）；`MockProviderFlowTest.rememberingThroughTheMockProvider…`（另一个新会话 `recall_memory` 找到 ARCHIVAL 事实） | **Mock 验证通过** | 同上；真实 JAR：`live-check.txt`（SQLite `memory_entries` 行、重启后仍在） |
| 27.4 | 审计对账：Session ID、`llm_calls`/`tool_invocations` 数量与 success/error、无重复工具执行、无额外模型调用 | `JpaLlmCallAudit`、`JpaToolCallAudit`、`JpaAgentSessionStore` | 上述各测试逐表断言；`SessionApiControllerTest` 等 | **Mock 验证通过** | 同上 |
| 27.5 | CLI、REST、管理台读同一持久化来源 | `JpaAgentSessionStore`、`ToolApiController`、`SessionApiController`；`BootCliRuntime` | boot `MockProviderFlowTest.theCliTheRestApiAndTheDashboardReadTheSameDatabase`（CLI 在自己的 Spring 上下文里跑 `chat`，REST 读到同一会话、同一审计行；`/info` 的会话数等于表行数）；`MockAgentE2ETest.restAndTheConsoleReadTheSameRows…` | **Mock 验证通过**（"管理台"指它调用的同一批 REST 端点；页面本身另见 27.9） | 同上 |
| 27.6 | Spring AI 自动工具执行与既有 `ToolExecutor` 不重复调用 | `ProviderService`（`internalToolExecutionEnabled(false)`） | boot `MockAgentE2ETest`：真实 Spring AI `OpenAiChatModel` 打到假服务器；Fixture 只被请求 1 次；变异检查见上 | **Mock 验证通过**（真实 Spring AI 客户端代码路径；真实 DeepSeek 未验证） | 同上 |
| 27.7 | 失败路径一：Provider 调用失败 | `ProviderService`、`ModelFailures`、`ProviderConfiguration`（**本周修复**，见下） | boot `MockAgentE2ETest.aProviderThatCannotBeReachedIs503…`：REST 503 `Model provider unavailable`；`llm_calls` 1 行 `success=0`；不含密钥；随后健康请求正常。provider `ProviderRetryPolicyTest`（3） | **Mock 验证通过** | `evidence/mvn-clean-verify.txt` |
| 27.8 | 失败路径二：Sandbox 拒绝工具 | `AllowListSandbox`、`RegistryToolExecutor`、`AuditedToolExecutor` | boot `MockAgentE2ETest.aSandboxRefusalIs…`：运行本身 200；审计 `success=0, SandboxDenied`，原因 `Path outside allowed WRITE roots`；文件未创建；审计表和 TOOL 观察结果都没有路径；模型在工具结果里被告知 `SANDBOX_DENIED` | **Mock 验证通过** | 同上 |
| 27.9 | 失败路径三：Tool 执行异常 | `RegistryToolExecutor` | boot `MockAgentE2ETest.aToolThatThrows…`：异常消息含 `secret-token-123`、`hunter2`、`prod.db`；审计行是 `Tool error: IllegalStateException`；三者都不在会话、`llm_calls`、`tool_invocations` 或 REST 响应里；服务继续健康 | **Mock 验证通过** | 同上 |
| 27.10 | 管理台显示真实数据 | `oryxos-web/src/main/frontend` | 前端 vitest 28；无头 Chrome 对六页 27/27（回归） | **Mock 验证通过**（自动化；**无人工浏览器验收**） | `evidence/dashboard-headless-run.txt` |
| 27.11 | 如课件要求：需要真实密钥的外部模型测试（`HumanTriggerFlowIT`） | — | — | **尚未实现 / 未验证**：没有可用密钥；课件是否要求未知（等待核对）。不伪造运行 | — |

### C1 中发现并修复的真实缺陷

失败路径测试第一次运行就**挂住了几分钟**：线程转储显示 `OpenAiChatModel` 在 Spring AI 默认的重试模板里睡眠——默认重试 10 次、指数退避可达分钟级，而且连接被拒也会重试。结果是模型不可达时 REST 请求线程被占住很久，第 26 节文档里的"503 `Model provider unavailable`"在真实环境里**等不到**。

修复（`ProviderConfiguration`，只改 Provider 的连接行为，不改公共接口）：只在服务端明确说"稍后再试"（`TransientAiException`，HTTP 429/5xx）时重试，最多 3 次、间隔 300 ms；连接被拒立即失败；连接超时 5 秒、读取超时 120 秒（因此挂起的 Provider 会走 504）。单测 `ProviderRetryPolicyTest` 固定该行为。

## C2 — 第 28 节：稳定性与多 Agent

| # | Requirement | Source | Test | Result | Evidence |
|---|---|---|---|---|---|
| 28.1 | 定时任务启动后正确注册并落库；时区正确 | `ScheduleConfigParser`、`AgentScheduler`、`JdbcScheduledTaskStore` | boot `SchedulerStabilityE2ETest.everyScheduleOfEveryProfileIsRegistered…`：8 个任务全部 enabled 并入库；同一"09:00"在 UTC 与 Asia/Shanghai 的下次触发分别是 09:00Z 和 01:00Z。既有 `AgentSchedulerTest`（26）、`ScheduleConfigValidationTest`（13） | **Mock 验证通过** | `evidence/test-summary.md` |
| 28.2 | `runNow` 实际触发 `AgentService`；运行记录落库；`run_count` 更新 | `AgentScheduler.runNow`、`SchedulerConfiguration` | `SchedulerStabilityE2ETest.aManualRunAndACronRunBothGoThroughAgentService…`（模型 2 次/工具 1 次/Fixture 1 次；`task_executions` 行；`run_count` 2；会话 `scheduler:scheduler:sched-chain`）；真实 JAR `live-check.txt` | **Mock 验证通过** | 同上 |
| 28.3 | 手动触发与自动触发共用处理链路 | `AgentScheduler.fire`（cron 入口）与 `runNow` 都调 `execute` | 同上：先 `runNow` 再 `fire`，两次留下相同形态的记录（同会话、`llm_calls` 各 +2、`tool_invocations` 各 +1，只有 `trigger_type` 不同：manual / cron） | **Mock 验证通过**（cron 路径用 `fire()` 驱动，即触发器调用的同一个方法；**本轮没有等待真实 cron 到点**，B3 阶段有一次真实准点触发的历史证据，见 course-progress） | 同上 |
| 28.4 | 同一任务不重叠 | `AgentScheduler`（单任务单飞锁） | `SchedulerStabilityE2ETest.sixSimultaneousRunsOfOneTaskExecuteExactlyOnce`：慢模型（每次 400 ms），6 线程同时 `POST /run`：1 个 200、5 个 409；模型只被请求 2 次、Fixture 1 次、`run_count` 1、`task_executions` 1 行。既有 `AgentSchedulerTest`（并发 16 只执行 1 次） | **Mock 验证通过**（仅单进程） | 同上 |
| 28.5 | `enabled` 状态持久化；保留 B3 的 `runNow` 语义 | `AgentScheduler.update/runNow` | `SchedulerStabilityE2ETest.disablingIsPersisted…`：停用后 SQLite `enabled=0`；cron 触发被跳过且不写历史；**手动运行仍被允许且不会重新启用**（B3 语义未改） | **Mock 验证通过** | 同上 |
| 28.6 | 重启恢复：Session、长期 Memory、Scheduler 状态、`llm_calls`、`tool_invocations`，比较重启前后真实记录 | `SqliteMigrator`、各 JPA/JDBC 存储 | boot `FullStackRestartRecoveryTest`（Markdown 与 SQLite 两种记忆后端各一次）：启动上下文→会话/记忆/沙箱拒绝/定时运行/停用任务→关闭→再启动；六张表逐行转储**完全相同**，记忆文件与记忆接口读数相同；之后继续使用，旧审计行不变、新行追加。**真实 JAR（独立 JVM 重启）**：`week05_live_check.py` 对 6 张表逐表比较，全部相同 | **Mock 验证通过** | `evidence/live-check.txt`（"Restart"一节 9 项）、`evidence/test-summary.md` |
| 28.7 | 多 Agent 隔离：A 只许文件工具，B 只许 HTTP 工具——Tool 权限、Sandbox、Session、Scheduler 失败隔离 | `ToolContext.profile()`、`ProfileSandbox`、`SessionIds`、`AgentScheduler` | boot `MultiAgentIsolationE2ETest`：①各自只被提供自己列出的工具，对方工具回 `Tool not available`，且不执行、不产生审计；②`agent-files` 读 B 的目录被拒（`SandboxDenied`），内容未泄漏；`agent-web` 访问未授权的 `127.0.0.2` 被拒，Fixture 未被访问；③同一用户对两个 Agent 得到两个互不包含对方数据的会话；④一个 Agent 的定时任务失败（`max_iterations` 配错）不影响另一个 Agent 的任务，且各自会话独立。既有 `MultiAgentSchedulerIsolationTest`（3） | **Mock 验证通过** | 同上；真实 JAR：`live-check.txt`（"isolation"一节） |
| 28.8 | **长期 Memory 隔离**（不得用 Session 隔离冒充） | — | `MultiAgentIsolationE2ETest.longTermMemoryIsStillGlobal…_notIsolated`：**特征化测试**，断言 `agent-web` 能读到 `agent-files` 保存的记忆 | **尚未实现**：记忆仍是全局共享，没有 per-agent memory isolation，留给第 30 节。该测试故意固定"现状"，将来实现隔离时它必须被有意修改 | 同上 |
| 28.9 | 失败语义：技术成功 ≠ 业务成功（例：ReAct 返回了文字，但关键工具被 Sandbox 拒绝） | **新增** `ScheduleOutcomeApiController`（web，只读） | web `ScheduleOutcomeApiControllerTest`（8）；boot `SchedulerStabilityE2ETest.aRunWhoseKeyToolWasRefusedIsTechnicallyASuccess…`：被拒的定时运行 `task_executions.success=1`、`lastStatus=SUCCESS`（**既有 API 与存储不变**），但 `GET /schedules/{id}/executions/{executionId}/outcome` 返回 `technicalStatus=SUCCESS`、`toolDenied=1`、**`outcome=COMPLETED_WITH_TOOL_ISSUES`**，说明"不是已确认的业务成功"；正常运行为 `COMPLETED`，抛异常为 `FAILED`；模型不可达的定时运行记 `Agent run failed (ModelUnavailableException)` 且不含密钥 | **Mock 验证通过**（只读新端点；管理台页面仍用前端自己的等价判断，尚未改用该端点） | `evidence/live-check.txt`（真实 JAR：`COMPLETED_WITH_TOOL_ISSUES`） |

## 回归（第 4 周功能没有被破坏）

| 项 | 结果 |
|---|---|
| 第 4 周的 502 个测试 | 全部仍在且通过；总数 538（见 [acceptance-report.md](acceptance-report.md)） |
| 第 4 周 77 项真实 JAR 检查（`scripts/week04_live_check.py`） | 77/77（`evidence/week04-live-check-regression.txt`） |
| 管理台无头浏览器检查 | 27/27（`evidence/dashboard-headless-run.txt`） |
| 4 个 Windows 符号链接测试 | 仍跳过，如实记录 |
