# OryxOS 第一阶段总结（第 32 节）

Written for: 课程评阅者和项目所有者。读完这一页就能知道第一阶段做了什么、每件事用什么证据证明、哪些**没有**证明。细节在同目录的[需求矩阵](requirements-matrix.md)、[验收报告](acceptance-report.md)、[已知限制](known-limitations.md)和[运行手册](demo-runbook.md)。

**验证口径（全文统一）**

| 口径 | 含义 |
|---|---|
| **Mock 验证** | 只替换外部世界（模型、天气/新闻/GitHub 接口、聊天群接收端）；Agent 加载、ReAct、工具执行、沙箱、调度、记忆、SQLite 审计、REST、管理台都是真实实现 |
| **真实只读外部 API 验证** | 对公网真实服务发只读请求并记录结果：Open-Meteo、Hacker News Search（Algolia）、GitHub Search API |
| **未验证** | 没有运行过：真实 LLM、真实 Webhook、真实连续 48 小时、新闻 MCP、Linux/macOS 实际运行、人工浏览器验收、课程平台提交 |

脚本化的模型读取的是**同一份**指令、记忆段和工具结果，所以它证明"我们的流水线把正确的输入交给模型、并把模型的输出送了出去"，**不证明**真实 LLM 会照做。

## 1. 架构：Provider、ReAct、Tool、Sandbox、Memory、Scheduler

九个 Maven 模块，一个可执行 Spring Boot JAR，SQLite 存状态。模块依赖由 `ArchitectureRulesTest` 强制（`oryxos-core` 只依赖 JDK，`oryxos-web` 只依赖 core，等等）。

```
 CLI (Picocli)      REST controllers       AgentScheduler (cron)
       \                  |                      /
        +---------> AgentService.process(channel, userId, profile, message) <---+
                           |
                       ReActLoop
                  /                 \
   ModelGateway -> ProviderService     AuditedToolExecutor -> RegistryToolExecutor
   (Spring AI, 工具自动执行关闭)           -> Profile 白名单 -> Sandbox -> OryxTool
                           |
   SQLite: sessions, llm_calls, tool_invocations, memory_entries, scheduled_tasks, task_executions
```

| 部分 | 做什么 | 关键点 |
|---|---|---|
| Provider | 提供商名称 → Spring AI `ChatModel` | 工具自动执行**关闭**，由我们的 ReAct 循环决定是否执行并审计；连接/读取超时与重试有界（Provider 不可达时不会挂住请求线程） |
| ReAct | 模型 → 工具 → 模型，最多 `max_iterations` 轮 | 每次模型调用写 `llm_calls`，会话步骤 USER/ASSISTANT/TOOL/ASSISTANT 按序保存 |
| Tool | `http_get`、`file_read`、`file_write`、`shell_exec`、`notify`、`save_memory`、`recall_memory`（以及 `get_payment`） | 唯一执行工具的代码在 `oryxos-tool`；每次调用经过 `AuditedToolExecutor`，成功、拒绝、失败都写 `tool_invocations` |
| Sandbox | 文件根目录、主机、命令的应用层策略 | Agent 声明的范围与运维上限取**交集**；不声明即拒绝；每次工具调用按最新定义重新判断；**不是操作系统隔离** |
| Memory | Markdown（默认）、SQLite、Mem0 三个后端同一接口 | CORE 条目总在提示词里（标题 `## Long-term memory`），ARCHIVAL 按需检索；`AGENT.md` Agent 有私有记忆 |
| Scheduler | 每个 Agent 声明 cron + 时区 | 不重叠、状态与历史在 SQLite、重启恢复、只通过 `AgentService` 运行；每次运行有 `execution_id` |

## 2. Agent Runtime 与业务 Agent 的分界

- **Runtime**（Java 代码）：加载、校验、注册、调度、ReAct、工具、沙箱、记忆、审计、REST/CLI/管理台。它不知道"天气"或"GitHub"。
- **业务 Agent**（数据）：`examples/agents/<name>/` 下的一个 `AGENT.md`（可再带 `skills/`、`scripts/`）。它只声明模型、工具白名单、通知渠道、定时、沙箱范围和一段指令。
- 第 31 节的三个业务 Agent **没有新增任何引擎代码**：加它们只是加三个目录。对 Runtime 的改动只有三处小的支撑（加载器忽略 `__pycache__`、outcome 接口增加逐次工具调用明细、管理台新增"Scheduled runs"面板），其余全部是 `examples/`、脚本、测试和文档。
- 判断标准：删掉 `examples/agents/`，Runtime 的全部测试仍然成立；装上它们，不需要改任何 Java 代码。

## 3. AGENT.md 插件化机制

- 目录即 Agent：`.oryxos/agents/<name>/AGENT.md` = YAML 头（沿用既有 Profile 字段）+ Markdown 正文（成为 `identity.prompt`）。
- 渐进式上下文：目录里的 `skills/`、`scripts/` 等资源**只按名字列出，不预加载**；Agent 需要时才用 `file_read` 读，所以不会把脚本源码塞进提示词。
- 同一条注册路径：启动扫描、`WorkspaceWatcher`（文件变化，带去抖与对账）、REST（`POST/PUT /api/v1/agents`）都走 `AgentLifecycleService.register(dir)`；内容哈希相同则不做任何事；注册失败会回滚文件、注册表和调度器。
- 删除是归档，不是物理删除。AI 生成文件的流程是"草稿（不保存）→ 人确认 → 保存并生效"。
- 通过 API 创建的 Agent，目录里只有 `AGENT.md`——天气 Demo 就是这样验证的。

## 4. REST 与 CLI 复用同一运行引擎

CLI、REST（`/api/v1/agents/{name}/invoke`、会话接口、管理台会话）、调度器（Cron 和"立即运行"）都调用同一个 `AgentService`。`AgentDemoSchedulerIT` 对三个 Agent 分别用 REST、CLI、真实 Cron 触发，断言模型收到的系统提示词逐字相同、工具集相同、工具序列相同。差别只在触发来源（会话 id 和 `task_executions.trigger_type`）。

## 5. 三个业务 Demo 的实现与验收

| Demo | 目录 | 定时 | 工具 | 数据源 |
|---|---|---|---|---|
| 天气 | `weather-daily/AGENT.md`（只有这一个文件） | 08:00 `Asia/Shanghai` | `http_get`、`notify` | Open-Meteo |
| 科技日报 | `daily-tech-digest/AGENT.md` + `skills/digest-format.md` | 09:00 `Asia/Shanghai` | `http_get`、`file_read`、`notify`、`save_memory`、`recall_memory` | Hacker News Search（Algolia） |
| GitHub 日报 | `github-daily/AGENT.md` + `scripts/github_trending.py` | 09:30 `Asia/Shanghai` | `shell_exec`（仅这一个脚本）、`notify`、`save_memory`、`recall_memory` | GitHub Search API |

数据源说明：HN 官方 `topstories` 只返回文章 ID，不是新闻，所以用 Algolia 的 HN Search API；GitHub 没有公开 Trending 接口，脚本用"创建日期窗口 + star 降序"近似，AI 方向对 5 个主题**分别查询再合并去重**，输出里写明"不是官方 Trending"。

| 验收层 | 结果 |
|---|---|
| Mock（Maven） | 天气 8、科技日报 9、GitHub 脚本 12、GitHub Agent 8、示例加载 4、示例安全默认 3、调度 3（含真实 Cron 触发）、审计 3、隔离 5、重启 1、跨天（可控时钟）4 |
| Mock（真实 JAR） | 三个 Demo 64/64：真实 Cron 各触发一次、REST/CLI/Cron 同路径、记忆偏好改变排序、失败不算业务成功、沙箱拒绝其它参数、重启后五张表和文件字节相同、Playwright 无头 Chromium 操作管理台 |
| 真实只读外部 API | Open-Meteo（直接 GET，以及经 JAR 真实 `http_get` 的天气 Agent）、HN Search 30 条、GitHub Search 经真实脚本跑通 |
| **未验证** | 真实 LLM（没有密钥）、真实 Webhook（没有授权目标）、真实 48 小时（"连续两天"只用可控时钟验证）、新闻 MCP（没有实现） |

## 6. 关键设计取舍、安全边界与下一阶段改进

### 取舍

| 取舍 | 选择 | 代价 |
|---|---|---|
| Agent 定义 | `AGENT.md` 为单一来源，不再双写 YAML | 旧 `profiles/*.yaml` Agent 仍共享全局记忆 |
| 技术状态 vs 业务结果 | 存储的状态语义不变（被拒绝的定时运行仍是 `SUCCESS`，B3 兼容）；新增只读 `outcome`（`COMPLETED` / `COMPLETED_WITH_TOOL_ISSUES` / `FAILED`）与管理台分开显示 | 两个概念并存，需要读者区分 |
| 审计对账 | V4 迁移给 `task_executions`、`tool_invocations` 加 `execution_id`，按 id 精确关联；旧行回退到时间窗，并带 caveat | 迁移不可回退 |
| 测试里的模型 | 127.0.0.1 上说 OpenAI 协议的假服务器 + **真实** Spring AI 客户端 | 不证明真实模型行为 |
| 并发 | 同一会话串行（`SessionGates`），调度不重叠只在单进程内 | 多实例各触发一次 |
| 沙箱 | 应用层策略，Agent 与运维上限取交集 | 不是 OS 隔离 |
| 数据源 | 免费、免密钥的只读接口 | HN 走第三方 Algolia；GitHub 榜单是近似 |

### 安全边界（读这一节再决定怎么运行）

- **没有认证。** 服务绑定 `127.0.0.1`；写操作和敏感读取只接受回环调用、无转发头、本地 `Origin`/`Host`。这是权宜之计，不是安全：本机任何进程都能调用。不要暴露端口。
- **沙箱是 JVM 内的策略**，不限制 CPU/内存/网络，不阻止被放行的程序做坏事。
- **Python 脚本不受域名白名单约束。** 沙箱只决定能不能以这些参数启动 `python`；脚本进程自己可以访问网络和文件。只用于自己审阅过的可信脚本。子进程环境被清空，拿不到 `GITHUB_TOKEN`；脚本有超时、1 MiB 上限、明确 User-Agent、限流不重试。
- **示例里的 `127.0.0.1` + `allow_private_networks: true` 只是本机演示**（让通知能发到本机接收端）。每个示例都在该行上方标注了 `LOCAL DEMO ONLY`；应用默认的运维上限是拒绝私有网络、不放行任何主机，仓库里没有任何配置打开它（`ExampleAgentsSafetyDefaultsTest` 守护这三点）。要发到真实群，先拿到明确授权，换成真实 URL，并**同时去掉**这两项。
- **外部内容是数据。** 新闻标题、仓库描述只以工具结果进入对话，不进系统提示词；测试用"Ignore all previous instructions…"验证了数据通道，但不证明真实模型不会被诱导。
- 通知默认只发本机接收端；没有任何真实 Webhook 被调用；仓库里没有密钥（`scripts/security_audit.py` 同时扫描历史）。

### 下一阶段改进（按优先级）

1. **真实 LLM 验证**：用环境变量里的密钥做一次有限、记录成本的真实运行，核对 tool call 格式、令牌计数、超时异常。
2. **认证与访问控制**：管理 API 加认证后才能离开本机。
3. **脚本隔离**：容器或低权限账号运行脚本，取代"可信脚本"假设。
4. **真实 Webhook 与连续运行**：有授权目标后发一次；做一次真实 48 小时无人值守观察。
5. **跨平台**：CI 里补 Windows/macOS 的 Python 命令名（`python` / `python3`）；人工浏览器验收。
6. **多实例调度**：当前单实例；这也是下一个课程项目（分布式延迟消息系统）要解决的问题。
