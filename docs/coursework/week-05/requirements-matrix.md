# 第 5 周需求对照矩阵（C1：第 27 节，C2：第 28 节，C3：第 29 节，C4：第 30 节，C5：第 31 节）

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

## C3 — 第 29 节：插件化 Agent（`AGENT.md`）

Agent Runtime（底座）不变：`AgentService`、`ReActLoop`、Provider、Tool、Sandbox、Memory、Scheduler、SQLite。业务 Agent 由 `.oryxos/agents/<name>/AGENT.md` 声明；新增 Agent **不改 Java、不重新打包、不新增 Spring Bean**，且复用同一个 `AgentProfile` 与 `AgentService`，没有第二套执行引擎。

| # | Requirement | Source | Test | Result | Evidence |
|---|---|---|---|---|---|
| 29.1 | 一个 `AGENT.md` 目录即可定义 Agent：YAML frontmatter + Markdown 正文；字段与现有 Profile 对应（name、description、provider、model、tools、notify_channels、schedules，以及 identity/skills/mcp_servers/channels/bootstrap/settings） | `AgentLoader`、`AgentMd`、`AgentDefinition`（provider，包 `io.oryxos.provider.agent`） | provider `AgentLoaderTest`（23，其中 1 个符号链接用例在无权限的 Windows 账号上跳过）、`DeriveProfileTest`（3：同样字段的 YAML 与 AGENT.md 派生出**相等**的 Profile） | **Mock 验证通过** | `evidence/mvn-clean-verify-c3.txt` |
| 29.2 | 只有一套 Profile Schema：校验复用 `ProfileLoader`，不创建第二套配置 | `ProfileLoader.parse(Map)`（从 `loadOne` 抽出，两处共用） | `DeriveProfileTest.anAgentMdAndALegacyYamlWithTheSameFieldsDeriveTheSameProfile`；既有 `ProfileLoaderTest` 不变 | **Mock 验证通过** | 同上 |
| 29.3 | 非法输入给出明确错误，不悄悄跳过：缺文件/缺 frontmatter/YAML 错误/重复键/缺必填/未知字段/名称与目录不符/未知 Provider/**未注册工具**/**错误 Cron**/类型错误/超大/非 UTF-8/identity.prompt 与正文冲突 | `AgentLoader`（消息带 `agents/<dir>/AGENT.md` 与字段名；未注册工具会列出已注册工具）、`ScheduleProfileValidator`（scheduler） | `AgentLoaderTest`；boot `AgentPluginIntegrationTest.aBadScheduleOrToolIsRefused…`（真实 `ToolRegistry` 与 `ScheduleConfigParser`：`cron is not a valid six-field cron expression`、`tool 'teleport' is not registered`、`unknown provider: gpt-nowhere`） | **Mock 验证通过** | 同上 |
| 29.4 | 启动扫描：一个坏目录不影响其他 Agent；旧 `profiles/*.yaml` 保持只读兼容 | `AgentDirectoryScanner`、`AgentStartup` | provider `AgentScanRegisterTest`（13）；boot `AgentPluginIntegrationTest`（9） | **Mock 验证通过** | 同上 |
| 29.5 | YAML 与 AGENT.md 同名：不静默覆盖、不随机选择，确定性冲突错误 | `AgentStartup.conflictMessage`：旧 YAML 保持生效，AGENT.md 目录不注册并报告；运行时 `AgentConflictException` | `AgentScanRegisterTest.theSameNameInYamlAndAgentMdIsADeterministicConflict…`（连续 3 次加载结果相同）、`aDirectoryWhoseNameBelongsToALegacyProfileIsRefusedAtRuntimeToo`；boot 同名用例 | **Mock 验证通过** | 同上 |
| 29.6 | 动态 Registry：线程安全；`register/remove/exists/get/list`；注册与热更新复用启动时的同一校验 | `ProfileRegistry`（不可变快照整体替换 + 写入串行化 + `ProfileChangeListener`）；`AgentLifecycleService.register` 对三种入口走同一路径 | provider `ProfileRegistryRuntimeTest`（7，含 4 个读线程 × 2000 次替换时读者永不见到半更新状态、8 线程同名并发注册只有 1 个成功） | **Mock 验证通过** | 同上 |
| 29.7 | 权限变更使 Sandbox 缓存失效；**取消目录权限后下一次工具调用立即被拒** | `Sandbox.invalidate`、`ProfileSandbox.invalidate`、`ProfileChangeListener`（boot 注册）、`RegistryToolExecutor` 每次调用按**最新**定义判断（进行中的回合也不能用旧快照） | tool `SandboxPolicyInvalidationTest`（7：允许→取消→立即拒绝；旧快照仍被拒；工具被移除→`SecurityException`；Agent 被移除→`ProfileNotFoundException`）；boot `AgentPluginIntegrationTest.tighteningAnAgentsDirectoryPermission…`（真实上下文：读 `private/` 成功→改 AGENT.md→reload→`SANDBOX_DENIED`，审计行 `SandboxDenied`） | **Mock 验证通过** | 同上 |
| 29.8 | Scheduler 动态注册/注销，句柄管理，重复注册幂等，稳定身份，不清空历史、不偷偷重新启用 | `AgentScheduler.registerProfile/unregisterProfile`（`ProfileScheduleSync`）；变更的计划先 `cancel` 旧句柄再注册；不再声明的计划被停止（行与历史保留）；已停用任务保持停用、`run_count` 不重置 | scheduler `AgentSchedulerRegisterTest`（10）；boot `AgentPluginIntegrationTest`（运行时复制进来的目录，Asia/Shanghai 计划立即出现在 `/schedules`） | **Mock 验证通过** | 同上 |
| 29.9 | 渐进式上下文：正文进 System Prompt；frontmatter 不重复；REFERENCE/skills/scripts **不预加载**；改正文后下次读最新、无旧指令残留 | `AgentLoader.prompt`（正文 + 文件**名**列表，从不读内容） | provider `ProgressiveDisclosureTest`（6） | **Mock 验证通过** | 同上 |
| 29.10 | 脚本安全：复用现有 Sandbox，Agent 不能扩大系统级范围；加载从不执行脚本；不把第三方脚本宣称为隔离执行 | 既有 `ProfileSandbox`（Profile ∩ 运维上限）；加载只列名字 | `ProgressiveDisclosureTest.loadingNeverRunsAScript`；既有 `ShellSecurityTest`、`ProfileSandboxTest` | **Mock 验证通过**（应用层白名单**不是**操作系统隔离，见 `known-limitations.md`） | 同上 |
| 29.11 | 真实 JAR：Agent 目录放入临时工作区，REST 与 CLI 都能看到，并用 Mock Provider 运行 | `BootCliRuntime.listProfiles`（现在合并 `agents/`） | `scripts/week05_agents_live_check.py --stage c3` | 见 `evidence/live-check-c3.txt` | `evidence/live-check-c3.txt` |

## C4 — 第 30 节：动态管理 Agent

C4 在 C3 的 `register(agentDir)` 之上加了统一生命周期（`AgentLifecycleService implements AgentManagement`），三种录入方式（启动扫描、手工复制目录 → `WorkspaceWatcher`、REST 创建）都走同一个 `register`，不存在各自的校验逻辑。失败时文件、Registry、Scheduler 一起回滚。

| # | Requirement | Source | Test | Result | Evidence |
|---|---|---|---|---|---|
| 30.1 | 统一生命周期入口：create / get / list / update / archive / register / reload / validate | `AgentLifecycleService`（provider）；核心端口 `AgentManagement`、`AgentView` | provider `AgentLifecycleServiceTest`（14）；boot `AdminAgentFlowTest`（11） | **Mock 验证通过** | `evidence/mvn-clean-verify-c4.txt` |
| 30.2 | REST：`POST/GET /agents`、`GET/PUT/DELETE /agents/{name}`、`POST /agents/{name}/invoke`；创建用确定性脚手架 `{name, description}`，不依赖 LLM；删除 = 归档到 `.oryxos/archive/`，不物理删除 | `AgentManagementApiController`（web）、`AgentApiController.invoke`（沿用） | web `AgentManagementApiControllerTest`（16）；`AgentLifecycleServiceTest`（脚手架可运行、描述不能改变 frontmatter 结构、归档含记忆、同秒归档不覆盖、归档失败时 Agent 保持注册）；`AdminAgentFlowTest`（创建→查询→修改→会话→归档→重启） | **Mock 验证通过** | 同上；真实 JAR `evidence/live-check-c4.txt` |
| 30.3 | 失败回滚：无效 AGENT.md、未知工具、Scheduler 拒绝、保存多文件中途失败，都不出现半注册 Agent；更新 schedules 时旧句柄被取消 | `AgentLifecycleService.update/saveFiles/create`（先校验，写原子文件，失败还原） | provider `AgentUpdateRollbackTest`（7）；scheduler `AgentSchedulerRegisterTest`（旧触发器 cancel、新触发器注册）；真实 JAR（改 cron、删 schedule 后只剩一个任务/无任务，历史保留） | **Mock 验证通过** | 同上 |
| 30.4 | `WorkspaceWatcher`：启动扫描 + `WatchService`；复制目录自动注册、编辑重载、删除注销；坏目录只影响自己；事件去抖；不接受半写入文件；Windows 可重复 | `WorkspaceWatcher`（防抖 + 状态核对 `reconcile()` 兜底 + 原子替换文件 + 内容哈希使同内容为空操作） | provider `WorkspaceWatcherTest`（10，真实文件事件，轮询等待而非固定 sleep：30 次写入合并为 ≤3 次注册、半截文件不被应用、编辑器式原子替换、`reconcile` 兜底、关闭后可再起）；真实 JAR（复制/编辑/删除/坏目录/半写入） | **Mock 验证通过** | 同上 |
| 30.5 | **每个 Agent 独立 Memory**（补 C2 缺口）：A 不能读/改 B 的私有记忆；新会话仍能读本 Agent 的；归档时记忆随目录走；旧全局 `MEMORY.md` 保留且**不**自动复制给任何 Agent；Mem0 不能证明隔离就明确报错 | 核心端口 `AgentMemories`，`PerAgentMemories`、`MemoryServices`（memory）；Markdown 存 `agents/<name>/MEMORY.md`，SQLite 用新的 `memory_entries.agent_name` 列；显式从 `ToolContext.profile()` 取 Agent，**没有新增 ThreadLocal** | memory `PerAgentMemoryIsolationTest`（10：工具与 prompt 都隔离、两个后端、全局不被复制进私有、并发写不丢、Mem0 式后端拒绝）；boot `AdminAgentFlowTest`（文件落在各自目录、全局文件不含私有事实、重启后仍在）；真实 JAR | **Mock 验证通过**。**范围**：只有 `AGENT.md` Agent 有私有记忆；旧 `profiles/*.yaml` Agent 没有目录，**仍共享全局记忆**（`MultiAgentIsolationE2ETest` 特征化测试保留，注释已更新） | 同上 |
| 30.6 | **Scheduler 执行 ID**，消除 C2 的时间窗启发式：每次执行一个唯一 `execution_id`；ToolExecutor 审计带该 ID；outcome 按 ID 精确查询；V4 迁移；历史记录保留 | `Migrations` V4（`tool_invocations.execution_id`、`task_executions.execution_id`、`memory_entries.agent_name`，附索引）、`ToolCallEvent`/`ToolCallRecord`/`TaskExecution` 新字段（旧构造器保留）、`AgentToolExecutor` 4 参数默认方法、`AgentScheduler.execute`、`ScheduleOutcomeApiController`（有 ID 按 ID，无 ID 回退时间窗并带 `caveat`） | storage `SqliteMigrationTest`（22，含 v3→v4 升级保留每一行、重复升级无变化）；boot `SchedulerExecutionCorrelationTest`（5：**同一 Agent 的两个任务并发**时，旧时间窗对每个运行都查到 2 条调用，按 ID 各 1 条；拒绝/成功归属正确；同任务两次执行不串账；聊天调用无 ID）；web `ScheduleOutcomeApiControllerTest`（11） | **Mock 验证通过**。`llm_calls` 没有加执行 ID（outcome 不需要） | 同上；真实 JAR（工具行 ID = 执行行 ID） |
| 30.7 | 固定管理会话：`GET /agents/{name}/session`、`POST /agents/{name}/session/messages`；复用 `AgentService`；**同一 Session 并发写入不再覆盖历史** | 会话 ID `admin:console:<name>`；`AgentService` 每会话串行（`SessionGates`，按需创建/释放的细粒度锁） | core `AgentSessionConcurrencyTest`（4：两个同时消息不丢答案且一次一个；20 条消息全部按序落库；不同会话仍并行；失败的运行释放锁。**变异检查**：让锁失效后其中 2 个失败）；web 控制器测试；真实 JAR（6 个并发消息，7 问 7 答成对） | **Mock 验证通过** | 同上 |
| 30.8 | AI 生成文件：`POST /agents/{name}/generate-files`（描述→Provider→草稿→Schema 校验）与 `POST /agents/{name}/files`（确认保存）；LLM 只生成草稿，不自动落盘、不运行脚本；非法 YAML 返回可读错误；不泄漏 Prompt/Token；无有效 Provider 明确报错；生成调用有审计 | `AgentDraftService`（只接受 Markdown 草稿、最多 5 个文件、不接受 scripts）、`AgentLifecycleService.saveFiles` | provider `GenerateTest`（11）；boot `AdminAgentFlowTest`（本地 OpenAI 协议假服务器给出草稿：生成后磁盘不变、`llm_calls` 有 `admin:generator:<name>` 行、响应不含 Prompt/Key；确认后才生效；非法草稿 400）；web 控制器测试（Provider 未配置 → 409）；真实 JAR（Python 假模型服务器） | **Mock 验证通过**；**真实 LLM 生成未验证** | 同上 |
| 30.9 | 工作区 API：`GET /workspace/tree`、`GET/POST /workspace/file`；禁止 `../`、绝对路径逃逸、符号链接/Windows Junction；限制类型、大小、目录；不能编辑数据库/密钥/配置；保存 AGENT.md 触发完整校验与重注册，失败保留旧 Agent | `WorkspacePaths`（逐段校验 + `toRealPath` 包含检查 + 逐级拒绝链接/重解析点）、`AgentWorkspace`、`WorkspaceApiController` | provider `AgentWorkspaceTest`（13，其中 1 个符号链接用例按权限跳过；**Windows Junction 用 `mklink /J` 真实运行**，树不会走进去）；web `WorkspaceApiControllerTest`（6）；真实 JAR（`../`、绝对路径、`C:/`、反斜杠被拒） | **Mock 验证通过**（过程中发现并修复：Junction 目录下的文件会出现在树里；`%` 现在也被拒绝） | 同上 |
| 30.10 | 本地管理安全边界：默认只监听 loopback；校验 Host/Origin；限制跨站写；不开宽松 CORS；删除/重要修改 UI 二次确认 | `server.address: 127.0.0.1`（已有）；`LocalAccess`（已有，所有新端点都调用）；无 CORS 配置 | web 两个控制器测试：**每个端点 × 4 种攻击（非本机地址、跨站 Origin、重绑定 Host、转发头）全部 403**，且底层服务零调用；boot `AdminAgentFlowTest`（写操作跨站 403、`server.address=127.0.0.1`）；真实 JAR（跨站 POST 403、重绑定 Host 403、局域网地址不可达） | **Mock 验证通过**。**仍然没有认证**：这是本机防护，不是身份验证 | 同上 |
| 30.11 | Vue 管理台：Agent 列表与详情五个部分（基本信息、生成、文件、会话、记忆）；真实 REST 数据；加载/空/错误状态；Scheduler 页读取准确的 outcome | `AgentsView`、`AgentDetailView`、`components/agent/*`；`SchedulerView` 改读 `/executions/{id}/outcome` | 前端 vitest 38（原 28 不变 + 新增 10：列表/问题目录/创建与拒绝、归档二次确认、生成不自动保存、文件树与保存失败、会话、共享记忆提示）；真实 JAR + Playwright 无头 Chromium（创建→概览→会话→改坏 AGENT.md 被拒→归档二次确认） | **Mock 验证通过**（自动化浏览器；**没有人工浏览器验收**） | `evidence/live-check-c4.txt` |
| 30.12 | 创建→查询→修改→触发→归档→重启恢复 | 同上 | `AdminAgentFlowTest`（第 2～11 步）；真实 JAR（重启前后六张表逐行相同、归档 Agent 不回来、Beta 的文件与私有记忆不变） | **Mock 验证通过** | 同上 |

## C5 — 第 31 节：三个业务 Agent Demo

底座没有新增引擎：三个 Agent 只是 `examples/agents/` 下的 `AGENT.md` 目录，由第 29、30 节的加载、生命周期、调度、沙箱、记忆、审计运行。运行手册见 [demo-runbook.md](demo-runbook.md)，证据见 `evidence/c5/`。**"Mock" 只替换外部世界**（模型、天气/新闻/GitHub 接口、聊天群接收端）；AgentLoader、ReActLoop、ToolExecutor、Sandbox、Scheduler、Memory、SQLite、REST、管理台都是真实实现。脚本化的模型读取的是**同一份**指令、记忆段和工具结果，因此证明的是"我们的流水线给了模型正确的输入、并把它的输出送了出去"，**不证明真实 LLM 会照做**。

| # | Requirement | Source | Test | Result | Evidence |
|---|---|---|---|---|---|
| 31.1 | 三个可版本控制的示例目录：`weather-daily/AGENT.md`；`daily-tech-digest/AGENT.md + skills/digest-format.md`；`github-daily/AGENT.md + scripts/github_trending.py`；运行时装入 `.oryxos/agents/` | `examples/agents/**`、`scripts/c5_install_agents.py` | boot `ExampleAgentsLoadTest`（4：原样提交的文件通过同一加载器/工具检查/沙箱解析/Cron 解析；权限最小：天气只有 2 个工具、一个外部主机，GitHub 只有 1 个脚本且没有 `http_get`，三者都没有 `file_write`，默认只通知本机接收端） | **Mock 验证通过** | `evidence/c5/mvn-clean-verify-c5.txt` |
| 31.2 | Demo 1 天气：Open-Meteo，仅 `AGENT.md`，08:00，穿搭建议，`notify` | `weather-daily/AGENT.md` | boot `WeatherDailyAgentTest`（8：**API 创建后目录里只有 AGENT.md**；手动触发 3 次模型调用 / 1 次天气请求 / 1 条通知；审计 `http_get ok, notify ok`；会话 6 条按序完整；模型只被提供 2 个工具，提示词不含 frontmatter 和 webhook 地址；域名被拒→`SandboxDenied` 且通知写“天气获取失败”、不编造；API 500；接收端 500；缺字段） | **Mock 验证通过** | 同上；真实 JAR `evidence/c5/live-check-c5.txt` |
| 31.3 | Demo 2 科技日报：`file_read` 读格式文件、读 Agent 专属记忆偏好、5–8 条、标题+点评+来源链接、`notify` | `daily-tech-digest/**` | boot `TechDigestAgentTest`（9：格式文件**未预加载**（首个提示词只有文件名），内容经 `file_read` 结果进入下一次请求，审计含 `file_read`；**偏好来自该 Agent 自己的 `MEMORY.md`，同一批新闻排序改变**（无偏好按分数；有偏好 AI/LLM/GPU 条目在前）；每条有来源链接，无链接的退回 HN 条目页；无新闻→“今天没有可用的科技新闻”不编造；新闻 503→工具失败、outcome 为 `COMPLETED_WITH_TOOL_ISSUES`；标题里的“Ignore all previous instructions…”只以工具结果出现、Agent 没有 shell；格式文件路径被改到声明目录之外→`SandboxDenied`，秘密不进模型） | **Mock 验证通过** | 同上 |
| 31.4 | Demo 3 GitHub 日报：受信 Python 脚本经 `shell_exec`，输出 today/month/ai JSON，三段日报，偏好影响 AI 重点，`notify` | `github-daily/**` | boot `GitHubTrendingScriptTest`（12，真实 Python 对本地 GitHub 夹具：创建日期窗口 + star 降序的查询参数、**每个 AI 主题单独查询再合并去重**（不是 AND）、分页、`--limit` 范围、空结果、限流退出码 3（不重试）、服务器错误/非 JSON/超大响应退出码 4、网络错误退出码 2、非法 `--api-base`/日期退出码 5、描述截短并去控制字符、stderr 为空）；`GitHubDailyAgentTest`（8：审计 `shell_exec ok, notify ok`；**脚本确实运行**（夹具收到查询）；脚本 JSON 进入下一次模型请求、脚本源码不在任何提示词；偏好“RAG”让 RAG 项目排到 AI 重点之首；别的参数/程序→`SandboxDenied` 且进程不启动；限流→“GitHub 日报失败”不编造；空→“今天没有查到数据”；描述里的指令只是数据） | **Mock 验证通过** | 同上 |
| 31.5 | 三条路径复用 `AgentService`：REST 手动、CLI、Scheduler Cron；**Cron 要真实观察到自动触发** | `AgentScheduler.execute`、`BootCliRuntime.chat` | boot `AgentDemoSchedulerIT`（3：三个 Agent 的 Cron 被改到几秒后，**调度器自己的触发器**各运行一次、各一条通知、审计按执行 ID 对账；REST/CLI/Cron 三条路径给模型的系统提示词逐字相同、工具序列相同、工具集相同）；真实 JAR（Cron、CLI `chat`） | **Mock 验证通过** | 同上 |
| 31.6 | 连续两天推送 | `AgentScheduler` + 可移动时钟 | scheduler `ScheduleCrossDayTest`（4：第 1、2 天 08:00 Asia/Shanghai 各一次 `cron` 运行，起点相差 24 小时，执行 ID 不同，下一天已排好，会话累积；第一天失败不影响第二天；两天之间停用则第二天跳过且保留历史） | **用可控时钟验证**。**真实连续 48 小时的无人值守运行没有发生，待验证** | 同上 |
| 31.7 | 审计对账、`execution_id` 关联准确、失败后不显示业务成功 | `AuditedToolExecutor`、`ScheduleOutcomeApiController` | boot `AgentDemoAuditIT`（3：三个运行逐个对账——模型服务器收到的请求数 = `llm_calls` 增量、工具行 = 预期序列、每次运行一行 `task_executions`、执行 ID 全不相同、调度类工具调用没有无 ID 的；REST 的 `/tool-calls` 与 SQLite 一致；天气+通知都失败 → `COMPLETED_WITH_TOOL_ISSUES`，存储的技术状态仍是 B3 语义） | **Mock 验证通过** | 同上 |
| 31.8 | 每个 Agent 隔离：记忆、工具、权限、会话 | 第 30 节的私有记忆 + 每 Agent 沙箱 | boot `AgentDemoIsolationTest`（5：各写各的 `MEMORY.md`、天气 Agent 没有记忆文件、全局文件不含偏好；偏好不进别的 Agent 的提示词；各自只被提供自己的工具；未列出的工具 `SecurityException`、已列出的工具仍受各自沙箱限制（技术日报只能读 skills 目录、天气不能访问新闻主机、GitHub 只能跑一个脚本）；会话与工具审计不串） | **Mock 验证通过** | 同上 |
| 31.9 | 重启后配置仍可用 | 同第 28/30 节 | boot `AgentDemoRestartIT`（1：重启前后五张表和三个 Agent 的 `AGENT.md`/`MEMORY.md` 逐行/逐字节相同；偏好、调度计数、时区保留；重启后仍能运行）；真实 JAR（重启后五张表相同、文件字节相同） | **Mock 验证通过** | 同上 |
| 31.10 | 真实 fat JAR 端到端：加载、API 和管理台可见、手动触发、真实 Cron、工具与记忆、通知接收、精确执行 ID 对账、重启 | `scripts/week05_c5_live_check.py` | 64 项（见证据）：含 Playwright 无头 Chromium 打开 Agents 列表、详情、确认后运行、查看工具调用、Files 页看到真实 AGENT.md/脚本/技能文件、会话页发消息、记忆页看到私有记忆、Scheduler 页 | **Mock 验证通过**（自动化浏览器；无人工验收） | `evidence/c5/live-check-c5.txt` |
| 31.11 | 管理台：在列表里找到、看配置和文件、手动触发、看会话、看专属记忆、看 Scheduler 记录、看 notify 与工具结果 | 复用第 30 节五标签页；**最小新增** `RunsPanel`（Overview 里的“Scheduled runs”）+ outcome 接口新增 `calls` 明细 | 前端 vitest 42（新增 4：列出最近运行并按需展开每个工具调用，拒绝显示为 ✗ + `SandboxDenied`；运行需确认、技术状态与工具审计分开；无 schedule 时说明；拒绝时显示服务器消息）；web `ScheduleOutcomeApiControllerTest`（12，+`calls` 顺序） | **Mock 验证通过** | 同上 |
| 31.12 | Tier B 真实外部服务（各自记录成功/未验证） | `scripts/week05_c5_external_check.py` | 见 `evidence/c5/external-tier-b.txt` | **真实外部服务验证通过**：Open-Meteo（直接 GET 与经 JAR 的真实 `http_get`）、HN Search（Algolia）、GitHub Search API（经真实脚本，未认证）。**未验证**：真实 LLM（没有 Key）、真实 Webhook（没有授权目标） | `evidence/c5/` |

## 回归（第 4 周功能没有被破坏）

| 项 | 结果 |
|---|---|
| 第 4 周的 502 个测试 | 全部仍在且通过；总数现为 786（见 [acceptance-report.md](acceptance-report.md)）。因新增 V4 迁移，仅把 `SqliteMigrationTest`/`MigrationStartupTest`/`week04_live_check.py` 里写死的"最新版本 = 3"改为 4（并新增 V3→V4 升级测试），断言没有放宽 |
| 第 4 周 77 项真实 JAR 检查（`scripts/week04_live_check.py`） | 77/77（最终 JAR，`evidence/week04-live-check-regression.txt`） |
| 管理台无头浏览器检查 | 27/27（`evidence/dashboard-headless-run.txt`） |
| 4 个 Windows 符号链接测试 | 仍跳过，如实记录 |
