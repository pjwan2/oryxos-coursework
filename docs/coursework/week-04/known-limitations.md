# 已知限制与未验证项（第 4 周）

写在前面：下面每一条都是**没有被实际验证、没有实现、或有明确缺口**的东西。它们不是"通过"。逐项对应的设计细节见同目录四份设计文档。

## A. 外部依赖：从未真实运行过

| # | 项 | 实际情况 | 影响 |
|---|---|---|---|
| A1 | **真实 LLM** | 所有测试与运行都用本地 Mock 模型（`MockChatModel`）。`ProviderConfiguration` 能建 DeepSeek 客户端（需要 `DEEPSEEK_API_KEY`），但从未带真实密钥运行过；真实模型是否会按预期产生 tool call 未知。 | 不能声称真实 ReAct 循环在真实模型上验证过。503/504 的映射只用合成异常测过，没见过真实超时异常链；401 之类密钥错误会走 500。 |
| A2 | **真实 MCP 服务器** | `McpClient` 只对测试替身运行（`McpTest` 4 个）。 | MCP 工具在真实服务器下的行为、失联处理未验证。 |
| A3 | **真实 Webhook** | `NotifyTool`/`WebhookNotifyAdapter` 只对记录型假接收器测试，没有向任何真实地址发过请求。 | 通知送达、真实 5xx 行为未验证。 |
| A4 | **真实 Mem0** | `Mem0MemoryStore` 的请求/响应形状按已知 REST 文档编写，只对假 Mem0 服务跑过契约测试。Mem0 云端可能异步写入（违反"写后立即可读"）；未处理分页。 | 不证明与真实 Mem0 兼容。 |

## B. 跳过的测试

**4 个测试被跳过**，全部在 `oryxos-tool/.../PathSecurityTest`：Windows 账号没有创建符号链接的权限（`A required privilege is not held by the client`），所以"指向根目录外的符号链接不能被跟随"等 4 个用例报告为 *skipped*。

- 同一条解析路径（`toRealPath`）由 **Windows junction 逃逸测试**覆盖，该测试执行并通过。
- 因此：**符号链接逃逸在本机没有被实际验证。** 需要在 Linux/macOS 或开启 Windows 开发者模式后重跑这 4 个测试。
- 本周所有构建报告都如实记录为"494/502 个测试中 4 个跳过"，没有把跳过算作通过。

## C. 没有实现的生产级安全控制

| # | 缺失 | 说明 |
|---|---|---|
| C1 | **没有认证、授权、用户或令牌** | 管理 API 和 Vue 管理台仅靠"绑定 127.0.0.1 + `LocalAccess`（回环对端、无转发头、本地 Origin、本地 Host）"。同一台机器上的任何进程或用户都能调用。**这不是认证，不得暴露到公网或放在反向代理后面。** |
| C2 | **没有 OS 级隔离** | Sandbox 是 JVM 内的策略检查：不限 CPU/内存/磁盘/进程数；不能阻止被允许的程序自己做坏事；同一 OS 用户的其他进程可直接读写同一批文件；不防内核/JVM/依赖漏洞。需要容器、低权限账号和网络策略作为外层。 |
| C3 | **DNS 重绑定未消除** | 先解析并检查所有地址，随后 `HttpClient` 会再次解析，两次结果可能不同。需要连接时固定 IP 或出站代理/防火墙。 |
| C4 | **检查与使用之间的竞态（TOCTOU）** | 路径检查通过后、打开前若有其他进程替换路径中的某一级为链接，仍有窗口；没有原子的 `O_NOFOLLOW` 式打开。 |
| C5 | 无审计之外的告警/限流 | 沙箱拒绝没有计数告警、没有速率限制。 |
| C6 | 无人工审批 | 模型可以自己写 CORE 记忆；没有高风险工具的人工确认。 |
| C7 | 无 TLS、无 CSRF 令牌、无速率限制 | 本地回环假设下未做。 |
| C8 | Swagger UI 与 `/v3/api-docs` 默认开启 | 启动日志有 SpringDoc 的提示，没有按环境关闭。 |
| C9 | "隔离风险标记" | 讲义整理包提到；代码里没有对应字段，只有文字说明 → **等待核对**。 |

## D. 设计与功能限制

1. **单实例调度器。** 防重叠锁在进程内存里；两个实例连同一个库会各触发一次。没有分布式锁、没有 exactly-once、不补跑停机期间错过的触发。
2. **会话并发。** 同一会话同时发两条消息会读-改-写互相覆盖；调度器与 REST 同时写同一会话同理。管理台不发消息，所以界面触发不到，API 可以。
3. **同一 Profile 的所有定时任务共用一个会话**（`scheduler:scheduler:<profile>`），历史无限增长，没有归档/滚动。
4. **任务"成功" = 没抛异常。** 沙箱拒绝的运行仍记 SUCCESS，拒绝只体现在 `tool_invocations`。是否应算失败需要产品决定；管理台已把两种状态分开显示。
5. **工具审计与调度执行按"会话 + 时间窗"匹配**，不是按执行 ID；同一 Profile 两个任务的运行时间重叠时可能混入对方记录。精确关联需要 `tool_invocations` 新增执行 ID 列（V4 迁移）。
6. **记忆是全局共享**（一个 `MEMORY.md` / 一张表 / 一个 Mem0 用户），不按 Agent 隔离；是否应隔离**等待核对**。
7. Markdown 记忆后端只在单进程内串行；两个进程同时写同一文件可能丢更新。
8. SQLite `LIKE` 只对 ASCII 不区分大小写，Markdown/Mem0 用 Java `toLowerCase`，带重音字母在后端间可能不一致。
9. Profile 清单是启动时快照；运行时增删 Agent（第 29/30 节）需要新的机制，沙箱的 Profile 缓存也没有驱逐策略。
10. `ProfileApiController`、Session 摘要等不感知运行时 Agent 变更。
11. 会话详情接口不返回创建/更新时间（只有列表有）。
12. 管理台没有分页/搜索；没有发送消息和删除会话的按钮（API 有）。
13. 虚拟线程只是配置开启，没有度量。
14. `storage` 依赖 `provider`（`LlmCallAudit` 端口在 provider 模块）；`SqliteMemoryStore` 在 memory 模块而不是 storage。两处偏差见 [module-boundaries.md](../../architecture/module-boundaries.md)，由 `ArchitectureRulesTest` 锁定为白名单。
15. 存在第二个 `AgentService` Bean（`lesson17AgentService`，脚本化模型，只服务第 17 节实验端点），见 design-decisions D-08。

## E. 验证方法的限制

1. **没有人工浏览器验收。** 页面由无头 Chrome + Puppeteer 自动检查并截图（`evidence/b4-live-run/`），这不等于有人逐页点验；不同浏览器、分辨率、可访问性都没有验证。该脚本含本机路径，不是构建门禁。
2. `evidence/live-check.txt`（`scripts/week04_live_check.py`）是**真实 JAR + Mock 模型 + 临时库**的检查，不涉及任何外部服务。
3. 架构规则测试是文本/XML 扫描（import 与 pom），不是字节码或运行时装配分析。
4. 业界方案对比（Memory、Sandbox、Scheduler）来自一般性知识，**未对照老师课件**。
5. 变异检查是手工做的（B2/B3 去锁、去防护看测试是否失败；本周对架构测试做了一次），没有自动化变异测试工具。

## F. 课程资料缺口（等待核对）

- 老师的原始课件（第 16～32 节）、正式作业提交说明、评分标准：**仓库里没有**。本周所有"按课程要求"的判断以任务书和讲义整理包为依据，已在 [requirements-matrix.md](requirements-matrix.md) 标明。
- 作业如何提交（上传源码包还是仓库地址）、截止日期、是否需要讲解视频或真实 LLM 运行证据：**等待核对**。
- 路径命名（如 `/api/v1/tool-calls`）、会话创建语义（幂等 201/200）、`invoke` 使用的 channel（`api`）是任务书之外的取舍，**待确认**是否与课程一致。

## G. 工程环境限制

- 构建需要 Node ≥ 20.19 和 npm（前端），否则 `oryxos-web` 的 enforcer 规则会让 Maven 构建失败并给出命令。
- `npm install` 对测试依赖链上的 `abbrev@5` 有 `EBADENGINE` 警告（要求 Node ≥ 22），仅开发/测试依赖，不进入产物。
- 本地 Git 提交不等于老师能访问源码；本周**没有推送任何远程**，也没有登录课程平台。
