# 第 4 周需求对照矩阵

对象：极客时间 AI 编程实战营第 4 周六节课。每一行：**课程要求 → 代码实现 → 自动化测试 → 运行证据 → 完成状态**。

## 要求来源与可信度（先读）

| 来源 | 可信度 |
|---|---|
| 作业任务书里列出的六个课程主题（Memory 原理/实现、Sandbox 原理/实现、定时任务、Web Service 与管理平台） | 用户提供，**是本矩阵的主线** |
| `../OryxOS_When_Homework_Implementation_v1/docs/EXECUTION_ROADMAP.md` 中"第 4 周 · 第 21–26 节"的交付物与最少验收 | 由此前的 AI 根据上传讲义整理，**不是老师的正式评分标准**；这里只用来补充"课程期望的交付物" |
| 老师的原始课件、正式作业提交说明、评分标准 | **仓库里没有。凡依赖它们的判断一律标"等待核对"，不猜测** |

课与课时号的对应（按讲义整理包）：1 = 第 21 节，2 = 第 22 节，3 = 第 23 节，4 = 第 24 节，5 = 第 25 节，6 = 第 26 节（Session 列表是第 27 节的补充，Scheduler 管理界面是第 28 节的一部分，已在 B4 完成）。

状态含义：**完成** = 代码、测试、运行证据齐全；**完成（有限制）** = 已实现并验证，但有明确未验证项，见同行说明；**部分**；**等待核对** = 取决于老师的正式要求。

测试数字来自 `docs/coursework/week04/evidence/test-summary.md`（本次干净克隆上 `mvn clean verify` 的报告）。`evidence/live-check.txt` 是对**真实可执行 JAR**的 77 项运行检查（`scripts/week04_live_check.py`）。

## 总表

| 课 | 主题 | 设计文档 | 实现 | 状态 |
|---|---|---|---|---|
| 1（21） | Memory 原理、业界方案、设计评审 | [memory-design.md](memory-design.md) | — | 完成（设计文档；业界对比为一般性知识，未对照课件） |
| 2（22） | Memory 实现与代码讲解 | 同上 | 见下 | 完成（有限制：真实 Mem0 未验证） |
| 3（23） | Sandbox 原理、业界方案、设计评审 | [sandbox-design.md](sandbox-design.md) | — | 完成（设计文档）；"隔离风险标记"仅以文档形式给出 → 等待核对 |
| 4（24） | Sandbox 实现与代码讲解 | 同上 | 见下 | 完成（有限制：4 个符号链接测试跳过、DNS 重绑定未消除、非 OS 沙箱） |
| 5（25） | 定时任务：原理、实现、代码讲解 | [scheduler-design.md](scheduler-design.md) | 见下 | 完成（仅单实例） |
| 6（26） | Web Service 与第一版管理平台 | [web-service-design.md](web-service-design.md) | 见下 | 完成（有限制：无认证、未做人工浏览器验收） |

## 第 1 课：Memory 原理解析、业界方案与设计评审

| 要求 | 实现位置 | 测试 | 证据 | 状态 |
|---|---|---|---|---|
| 区分短期与长期记忆 | 短期：`AgentSession`、`JpaAgentSessionStore`、`PromptBuilder`（窗口 20 条）；长期：`LongTermMemoryStore` | `PromptBuilderTest` 3、`RestartRecoveryTest` 4 | memory-design.md 第 1 节 | 完成 |
| 业界方案对比与取舍 | — | — | memory-design.md 第 2 节（**一般性知识，未对照课件**） | 完成（待核对） |
| 设计评审：需求、接口、数据、风险 | — | — | memory-design.md 第 3～8 节 | 完成 |
| 设计取舍与安全边界 | — | — | memory-design.md 第 8、10 节；[design-decisions.md](../../architecture/design-decisions.md) D-09 至 D-11 | 完成 |

## 第 2 课：Memory 实现与代码讲解

| 要求（来自讲义整理） | 实现位置 | 测试 | 运行证据 | 状态 |
|---|---|---|---|---|
| `LongTermMemoryStore` 门面，Markdown / SQLite / Mem0 后端 | `oryxos-core/.../memory/LongTermMemoryStore.java`；`oryxos-memory/.../{MarkdownMemoryStore,SqliteMemoryStore,Mem0MemoryStore}.java`；选择：`oryxos-boot/.../MemoryConfiguration.java` | `MemoryStoreContractTest`（同一 14 条断言）由 `MarkdownMemoryStoreTest` 22、`SqliteMemoryStoreTest` 16、`Mem0MemoryStoreTest` 20 继承 | live-check.txt "Memory, backend=markdown / sqlite" | 完成 |
| 写后立即可读，无缓存，跨会话可读 | `MemoryService.promptSection` 每次读存储 | 契约测试；`MemoryPromptIntegrationTest` 9、`SqliteMemoryPromptIntegrationTest` 9 | live-check：另一个用户召回成功 | 完成 |
| CORE 永不截断，ARCHIVAL 有上限；recall 只搜 ARCHIVAL | `MemoryService`；`RecallMemoryTool` | `MemoryServiceTest` 8、`MemoryToolsTest` 8 | live-check："recall searches ARCHIVAL only" | 完成 |
| `save_memory` / `recall_memory` 工具，仅 Profile 白名单内可用 | `SaveMemoryTool`、`RecallMemoryTool`、`MemoryContextLoader` | `MemoryToolsTest`、`ContextLoadersTest` 6 | live-check："agent without the tool ... cannot use memory" | 完成 |
| 重启后记忆仍在 | 三个后端 | `MemoryRestartTest` 3 | live-check："memory survives a JVM restart"（两个后端） | 完成 |
| Markdown 文件格式、手写内容保留、畸形文件不覆盖 | `MarkdownMemoryStore` | `MarkdownMemoryStoreTest` | live-check：`MEMORY.md` 含两个分区 | 完成 |
| 真实 Mem0 | `Mem0MemoryStore` | 只对**假 Mem0 服务**测试 | 无 | **未验证**（见 known-limitations） |
| 按 Agent 隔离记忆 | 当前是全局共享 | — | — | 等待核对（课程是否要求未知） |

## 第 3 课：Sandbox 原理解析、业界方案与设计评审

| 要求 | 实现位置 | 测试 | 证据 | 状态 |
|---|---|---|---|---|
| 威胁模型 | — | — | sandbox-design.md 第 1 节 | 完成 |
| 业界方案对比 | — | — | sandbox-design.md 第 2 节（**一般性知识，未对照课件**） | 完成（待核对） |
| 设计评审：权限来源与交集、失败默认拒绝 | — | — | sandbox-design.md 第 3 节；D-06、D-07 | 完成 |
| 说明应用层白名单不等于 OS 沙箱 | — | — | sandbox-design.md 第 8 节 | 完成 |
| "隔离风险标记"（讲义整理包提到） | 代码里没有标记字段 | — | 仅文字说明 | **部分 / 等待核对** |

## 第 4 课：Sandbox 实现与代码讲解

| 要求 | 实现位置 | 测试 | 运行证据 | 状态 |
|---|---|---|---|---|
| 沙箱接口 + 文件 / 域名 / 命令白名单 | `oryxos-core/.../sandbox/{Sandbox,SandboxSpec}.java`；`oryxos-tool/.../{ProfileSandbox,AllowListSandbox,SandboxPolicy,CommandRule}.java` | `SandboxSpecTest` 9、`ProfileSandboxTest` 12、`AllowListSandboxTest` 6 | live-check "Sandbox" | 完成 |
| 按 Profile 生效（权限 = Profile 声明 ∩ 运维上限；未声明全拒绝） | `ProfileSandbox.forProfile`；`ToolContext.profile()`；`ToolConfiguration` | `ProfileSandboxLoadingTest` 5、`SandboxIntegrationTest` 10 | live-check："Profile with no sandbox declaration is denied" | 完成 |
| 文件：`../`、符号链接/junction、UNC、盘符相对、ADS、设备名 | `FileReadTool`、`FileWriteTool`、`AllowListSandbox` | `PathSecurityTest` 18（**4 个跳过**） | live-check：`..` 遍历与越界写被拒、文件未创建 | 完成（有限制：符号链接未在本机验证，junction 已验证） |
| HTTP：主机解析、SSRF（元数据地址、私网、数字伪装 IP、重定向） | `HttpGetTool`、`AllowListSandbox` | `UriSecurityTest` 20 | live-check：`169.254.169.254`、`2130706433`、未列入主机均被拒 | 完成（有限制：DNS 重绑定未消除） |
| Shell：程序名 + 逐参数正则，无 shell 字符串 | `ShellTool`、`CommandRule` | `ShellSecurityTest` 10 | 单测（真实 JAR 未配置 shell 命令） | 完成 |
| 拒绝同样审计，且不泄漏凭证 | `AuditedToolExecutor`、`SafeErrorText` | `SandboxEnforcementTest` 12、`AuditedToolExecutorTest`、`SafeErrorTextTest` 9 | live-check：7 条 SandboxDenied 与 SQLite 一致，原因里无 IP/令牌 | 完成 |
| 非法沙箱配置启动失败 | `SandboxSpec.parse`、`ProfileLoader` | `SandboxStartupValidationTest` 7 | B2 记录（真实 JAR 退出码 1） | 完成 |
| OS 级强隔离（CPU/内存/网络/进程） | 无 | — | — | **未实现，且文档明确声明** |

## 第 5 课：定时任务模块

| 要求 | 实现位置 | 测试 | 运行证据 | 状态 |
|---|---|---|---|---|
| Profile 中的 cron + 时区 | `ScheduleConfigParser`、`AgentScheduler`（Spring `CronTrigger`） | `ScheduleConfigValidationTest` 13、`AgentSchedulerTest` 26 | live-check：任务按 Profile 注册，时区保留 | 完成 |
| 本地防重叠（finally 放锁） | `AgentScheduler`（单任务单飞锁） | `AgentSchedulerTest`（16 并发只执行 1 次；去锁后 3 个测试失败的变异检查见 B3 记录） | — | 完成 |
| 失败不拖垮调度器 + 失败日志/审计 | `AgentScheduler`；`TaskExecution`、`SafeErrorText` | `AgentSchedulerTest`、`ScheduledTaskE2ETest` 10 | live-check：失败运行记 `Agent run failed (...)` | 完成 |
| 复用 AgentService / Session | `SchedulerConfiguration` 注入 `springAiAgentService`；会话 `scheduler:scheduler:<profile>` | `ScheduledTaskE2ETest`、`MultiAgentSchedulerIsolationTest` 3 | live-check：会话 id 与 B3 一致 | 完成 |
| 状态与执行历史持久化、重启恢复 | `JdbcScheduledTaskStore`、`SqliteMigrator`、迁移 V3 | `ScheduledTaskStoreTest` 9、`SqliteMigrationTest` 20、`MigrationStartupTest` 6、`RestartRecoveryTest` 4 | live-check：杀进程重启后启停/计数/历史不变；`WebSmokeIT` | 完成 |
| 管理操作（列表、历史、立即执行、启停） | `ScheduleApiController`；Vue Scheduler 页 | `ScheduleApiControllerTest` 17；前端 vitest | live-check；headless Chrome 运行 | 完成 |
| 多实例 / 分布式 | 无 | — | — | 不在范围（单实例，已声明） |

## 第 6 课：Web Service 与第一版管理平台

| 要求 | 实现位置 | 测试 | 运行证据 | 状态 |
|---|---|---|---|---|
| Spring MVC 十端点（Session 5 个 + Agent invoke + profiles/memory/tools/health/info）+ Session 列表（第 27 节） | `oryxos-web/.../{Session,Agent,Profile,Memory,Tool,System}ApiController.java` | `SessionApiControllerTest` 22、`AgentApiControllerTest` 6、`QueryApiControllersTest` 11、`RestApiIntegrationTest` 11 | live-check "Web service" | 完成 |
| 统一响应与异常；400/404/500 不泄漏；503/504 | `ApiResponse`、`GlobalExceptionHandler`、`ModelFailures` | 同上 + `ModelFailuresTest` 5 | live-check：500 固定文本且不含内部配置词 | 完成（503/504 仅合成异常验证） |
| 32 KiB 消息上限、会话列表/历史最多 100 | `AgentInvoker`、`SessionApiController` | `SessionApiControllerTest`（按 UTF-8 字节边界） | live-check：32769 字节 → 400 | 完成 |
| 所有 Agent 执行走同一个 AgentService | `AgentInvoker` | `ArchitectureRulesTest` R5/R6/R11 | — | 完成 |
| `X-Trace-Id`、OpenAPI/Swagger、Prometheus | `TraceIdFilter`、springdoc、Actuator | `RestApiIntegrationTest`、`AdminUiTest` | live-check：`/v3/api-docs`、Swagger UI、`/actuator/prometheus` | 完成 |
| JDK 21 虚拟线程 | `application.yaml: spring.threads.virtual.enabled` | — | 配置存在（未单独度量） | 完成（仅配置） |
| Vue 3 + Vite 管理台：六页，真实数据，loading/empty/error | `oryxos-web/src/main/frontend/`；产物 `static/admin/` | vitest 28；`AdminUiTest` 6；`WebSmokeIT` 5 | live-check；`evidence/b4-live-run/`（headless Chrome 27+5 项，13 张截图） | 完成（有限制：**无人工浏览器验收**） |
| 由 Spring Boot JAR 托管 `/admin/`，子路由刷新不 404，`/api` 不受影响 | `AdminUiConfiguration`、`AdminIndexController` | `AdminUiTest`、`WebSmokeIT`（真实 JAR 内含前端资源） | live-check | 完成 |
| 前五页只读；不提前做 Agent CRUD | 前端 | 前端测试 | — | 完成 |
| 无认证，仅限本地可信访问 | `LocalAccess` + 绑定 127.0.0.1 | `SessionApiControllerTest`、`ScheduleApiControllerTest` | live-check：403 用例 | 完成（**不是认证**，已多处声明） |

## 作业提交规则

| 项 | 状态 |
|---|---|
| 提交方式（上传源码包还是仓库地址）、截止日期 | **等待核对**（仓库内无课程平台说明） |
| 老师的评分标准、是否要求 60～90 秒讲解 / 截图 | **等待核对**（讲义整理包的证据模板提到"自述 60–90 秒"，但那是整理包的建议，不是老师的规定） |
| 是否要求真实 LLM 的手动运行证据 | **等待核对**；目前没有任何真实 LLM 运行 |
