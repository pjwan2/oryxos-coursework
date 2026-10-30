# 第 5 周（C1 + C2）已知限制与未验证项

只写**没有验证、没有实现、或有明确缺口**的事。第 4 周的限制（[../week-04/known-limitations.md](../week-04/known-limitations.md)）依然成立，这里不重复，只写本阶段新增或需要强调的。

## A. 真实外部服务：全部未验证

| 项 | 状态 |
|---|---|
| 真实 LLM（DeepSeek 等） | **未验证**。"模型"是 127.0.0.1 上的假服务器（讲 OpenAI 聊天协议）和进程内 `MockChatModel`。真实 Spring AI 客户端代码路径被执行了，但真实服务商对 tool call 的响应细节、令牌计数、真实超时异常链都没见过；`llm_calls` 里的令牌数是假服务器给的固定值。 |
| `HumanTriggerFlowIT`（需要真实密钥的外部模型集成测试） | **尚未实现**。没有可用密钥；第 27 节课件是否要求它不得而知（等待核对）。没有写一个"会被跳过的空测试"来冒充。 |
| 真实 MCP、Webhook、Mem0 | **未验证**（同第 4 周）。 |
| 真实天气 API | **未验证**。天气来自 127.0.0.1 的 Fixture；为让沙箱放行回环地址，测试里 Profile 和运维上限都开了 `allow_private_networks`。这是测试配置，不是对公网天气服务的验证，也不是推荐的生产配置。 |

## B. 本阶段新增的行为与限制

1. **Provider 连接行为变了（修复）**：重试从 Spring AI 默认值改为"仅 429/5xx 重试 3 次、间隔 300 ms，连接被拒立即失败，连接超时 5 秒、读取超时 120 秒"。常量写在 `ProviderConfiguration`，没有做成配置项；读取超时 120 秒对长回答的模型可能偏短，也没有按 Profile 区分。
2. **`outcome` 端点是时间窗启发式**：新端点按"该运行的会话 + 从开始到结束后 1 秒"去匹配工具审计，不是按执行 ID 精确关联。同一 Profile 的两个任务共用一个调度会话，运行时间重叠时可能把对方的工具调用算进来。精确关联需要给 `tool_invocations` 增加执行 ID 列（V4 迁移），**本阶段没做**。只查最近 200 条执行，更早的返回 404。
3. **管理台还没用新端点**：Scheduler 页仍由前端自己的等价逻辑判断"技术状态 vs 工具审计"，没有改成调用 `outcome`。
4. **`lastStatus`/`success` 的语义没变**：被沙箱拒绝的定时运行仍存为 `SUCCESS`（B3 的产品决定，为保持 API 兼容未改）。只有新端点（和管理台）会把它标成 `COMPLETED_WITH_TOOL_ISSUES`。是否应把拒绝直接算作失败仍是待决定的产品问题。
5. **cron 真实到点触发本轮没有观察**：E2E 用 `AgentScheduler.fire()` 驱动 cron 路径（触发器调用的同一个方法）。B3 阶段有过一次真实准点触发的实机记录（见 course-progress），本阶段没有再等。
6. **重叠保护只在单进程内**：锁在内存里。6 个并发请求只执行 1 次，说明单进程内正确；两个实例连同一个库仍会各触发一次。
7. **重启恢复的范围**：比较了六张表、记忆文件和记忆接口，并有真实 JVM 重启；没有测试"写入中途被强杀"的崩溃一致性，也没有测试数据库升级（迁移）与重启叠加。

## C. 多 Agent 隔离的边界

- **已验证（Mock）**：工具白名单、Sandbox、Session、定时任务失败隔离。
- **尚未实现：长期 Memory 隔离。** 所有列出 `save_memory`/`recall_memory` 的 Agent 共用同一份记忆。`MultiAgentIsolationE2ETest.longTermMemoryIsStillGlobal…_notIsolated` 是一个**特征化测试**：它断言"B 能读到 A 保存的记忆"，目的是不让人把 Session 隔离当成 Memory 隔离。实现 per-agent memory（第 30 节）时，这个测试必须被有意修改。
- Sandbox 的 Profile 缓存仍没有驱逐策略；Profile 仍是启动时快照。

## D. 测试方法的限制

- 假模型服务器按"用户消息里的关键词 → 请求某个工具；最后一条是 tool 消息 → 给最终答案"脚本化。它证明了**我们的**循环、执行、审计、记忆、沙箱的行为，不证明真实模型会这样决策。
- 断言用的是模型调用/工具调用的**数量和审计行**，不是回答质量。
- 一个"Mock 验证通过"的结果不能当作生产可用的声明。
- 4 个符号链接测试仍在 Windows 上跳过（第 4 周已记录；在 GitHub Actions 的 Ubuntu 上的情形见 [../week-04/known-limitations.md](../week-04/known-limitations.md)）。
- 无头 Chrome 检查是自动化的，**没有人工浏览器验收**。

## E. 进入第 29/30 节前需要解决的问题

1. **Agent 定义的单一事实来源。** 现在是 `.oryxos/profiles/*.yaml`；第 29 节要改为 `.oryxos/agents/<name>/AGENT.md`。迁移期间不能让两个可写来源同时存在；`ProfileLoader`、`ProfileRegistry`、调度器的任务同步（`definition_hash`）都要一起考虑。
2. **可变的 Profile 目录。** `ProfileCatalog` 现在是启动时快照；动态增删 Agent 需要线程安全的注册表，并通知调度器（增删任务）、REST（`/profiles`）和沙箱缓存。
3. **沙箱缓存驱逐。** `ProfileSandbox` 按 Profile 对象缓存、无上限，Agent 被修改/删除时必须失效。
4. **per-agent memory。** 见 C 节；需要先决定隔离粒度（每 Agent 一份？共享 + 白名单？）并改 `LongTermMemoryStore` 契约和三个后端。
5. **工具审计的执行 ID**（V4 迁移），以消除 `outcome` 端点的时间窗启发式。
6. **会话并发**：同一会话同时发两条消息仍会读-改-写互相覆盖（第 4 周已记录），Agent 动态管理之后更容易遇到。
7. **管理 API 的边界。** 第 30 节会让管理台能创建/修改/删除 Agent，而系统仍**没有认证**；必须保持仅本机、确认步骤、校验错误展示，并先决定是否要做真正的认证。
8. 管理台改用 `outcome` 端点；以及真实 LLM 的 `HumanTriggerFlowIT`（有密钥时）。
