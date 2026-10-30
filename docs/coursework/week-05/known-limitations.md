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

## F. 第 29 节（C3：`AGENT.md` 插件化）新增的限制

1. **应用层白名单不是操作系统隔离。** `scripts/` 里的 Python 等脚本只有在 Profile 列出 `shell` 工具、且运维上限 `oryxos.sandbox.commands` 允许该命令时才会被运行；一旦运行，脚本进程自己可以访问它所在用户能访问的文件和网络，沙箱管不到。**只适用于可信的本地 Agent，不能把第三方不可信脚本说成"安全隔离执行"。**本阶段没有引入容器。
2. **Agent 读自己的 `REFERENCE.md` 需要运维放行。** 默认运维上限只含 `.oryxos/workspace`，所以默认情况下 Agent 虽然在 prompt 里看到文件名，却读不了 `.oryxos/agents/<name>/REFERENCE.md`；运维要把 `.oryxos/agents`（或该目录）加进 `oryxos.sandbox.read-roots`，Agent 再在自己的 `settings.sandbox.read_roots` 声明。Agent 不能自行扩大范围（交集规则）。
3. **启动时一个坏目录不会让应用启动失败**：它不注册，原因写入日志和 `AgentLifecycleService.problems()`（第 30 节再通过 REST 展示）。这与旧 `profiles/*.yaml` 里"非法沙箱声明会让启动失败"的做法不同：目录整体不注册，不存在"以不同权限运行"的风险。
4. **prompt 里的文件名清单只在重新加载时刷新。** 新增/删除 `REFERENCE.md`、`skills/`、`scripts/` 里的文件，要 reload（第 30 节由文件监听或保存 API 触发）才会反映到清单。
5. **旧 `profiles/*.yaml` 仍是只读来源，也不迁移。** 同名冲突时旧 Profile 保持生效，AGENT.md 目录被拒绝并报告（确定性、不随机）。
6. **取消注册 Agent 不删除调度行和历史**：任务不再触发、`/schedules` 列表里不再出现，`executions` 历史仍可查；同名 Agent 以后再注册时，已停用的任务仍然停用、`run_count` 不重置。
7. **`oryxos.scheduler.enabled=false` 时动态注册只校验并记录定义，不创建触发器。**
8. **进行中的回合**：`RegistryToolExecutor` 每次工具调用按 Agent 的**最新**定义判断（权限收紧立即生效，Agent 被移除则调用失败）；但同一回合里已发给模型的工具清单是回合开始时的快照，模型仍可能"请求"已撤销的工具，请求会被拒绝并审计。
9. **新增的符号链接用例**：`AgentLoaderTest.anAgentMdThatIsALinkIsRefused` 与既有 4 个一样，在没有创建符号链接权限的 Windows 账号上被跳过（如实计入跳过数）。目录联接（junction）逃逸用例 `AgentScanRegisterTest.aDirectoryThatEscapesTheAgentsRootIsRefused` 在 Windows 上用 `mklink /J` 真实运行。
