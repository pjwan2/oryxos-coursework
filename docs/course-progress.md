# 课程进度与验收证据

分支：`feature/spring-ai-react-integration`。所有结论来自本机实际执行；"无法验证"的项目单独列出，不算完成。

> **课程资料缺口**：仓库里只有 `docs/COURSE_MAPPING.md`（第 15 节映射）和 README，没有第 16～32 节的原始课件与完整命令/作业清单。
> 凡是"按课程清单补齐"的条目，下文标为 **待确认**，没有假装对照过。

---

## 阶段 A：第 3 周（15～20 节）

### 验收命令与结果

```bash
mvn -B -ntp spotless:apply           # BUILD SUCCESS
mvn -B -ntp clean verify             # BUILD SUCCESS，未使用 -DskipTests
```

| 模块 | 测试数 | 失败 |
|---|---|---|
| oryxos-core | 15 | 0 |
| oryxos-provider | 26 | 0 |
| oryxos-tool | 34 | 0 |
| oryxos-cli | 9 | 0 |
| oryxos-boot（Spring 集成） | 22 | 0 |
| **合计** | **106** | **0** |

`verify` 同时执行 Checkstyle、PMD、SpotBugs，全部通过；没有关闭、放宽或加排除规则。

### 对可执行 JAR 的实际运行验证（独立临时 SQLite）

| 检查 | 结果 |
|---|---|
| Manifest | `Main-Class: org.springframework.boot.loader.launch.JarLauncher`，`Start-Class: io.oryxos.OryxOsApplication` |
| `--version` / `status` / `profile list` / `tool list` | 正常；轻命令运行后数据库文件**未被创建**（未启动 Spring） |
| `chat payment-demo --user a1 tool:get_payment {...P1001}` | `Tool result: P1001 PENDING 120 AUD` |
| 再次启动新 JVM 对同一用户 chat | 同一 session 继续（跨重启恢复） |
| 查询不存在的支付单 | `tool_invocations`：`success=0, error_category=ToolReturnedFailure` |
| `chat ghost hi` | `Error: Unknown agent profile: ghost`，退出码 2，无堆栈 |
| `serve --server.port=8099` | 启动成功 |
| REST `/api/v1/health` | `{"code":0,...}` |
| REST `/api/v1/labs/payment/chat` | 200，写入 `web:r1:payment-demo` |
| REST 坏 JSON | 400（不再是 500） |
| `/actuator/health` | `UP` |
| `/actuator/prometheus` | 56 条 `jvm_` 指标 |
| Swagger UI / `/v3/api-docs` | 200，含 `labs/payment/chat` |
| 表计数 | sessions 4、llm_calls 6、tool_invocations 3；CLI 与 REST 会话互不混淆 |

### A1 Provider + ReAct

| 条目 | 状态 | 证据 |
|---|---|---|
| SpringAiResponseMapper / SpringAiModelGateway 接入 ReActLoop | 完成（上一轮） | `SpringAiResponseMapperTest`(10)、`SpringAiModelGatewayTest`(7)、`SpringAiModelGatewayIntegrationTest`(4) |
| 保存 Tool Call ID、JSON 参数、Tool Result | 完成 | `AgentMessage.requests/replies`；集成测试做了 JSON 往返 |
| 关闭 Spring AI 自动 Tool 执行 | 完成 | `internalToolExecutionEnabled(false)`，集成测试断言 |
| 最多 10 轮 | 完成 | `ReActLoop.DEFAULT_MAX_ITERATIONS=10`，Profile `max_iterations` 可调(1..100)，`ReActLoopTest` |
| Prompt 历史截断 | **本轮修复缺陷** | 截断可能从孤立的 TOOL 消息开始，真实 API 会拒绝；`PromptBuilder` 现在跳过它。`PromptBuilderTest` |
| 模型/工具调用成败均审计 | 完成 | `llm_calls`、`tool_invocations`；沙箱拒绝单独记为 `SandboxDenied` |
| AgentService / ProfileContext / 异常清理 ThreadLocal | 已有且通过 | `AgentServiceTest` |
| ContextLoader | **接口完成，尚无实现** | `ContextLoader` + `PromptBuilder` 支持注入；实现随 B1 Memory 提供，目前未在 Spring 装配中使用 |

### A2 CLI（Picocli）

| 条目 | 状态 |
|---|---|
| `chat`（单次与交互）、`serve`、`status`、`profile list`、`tool list` | 完成，`OryxCliTest`(9)、`CliEndToEndTest`(6) |
| 轻命令不启动 Spring | 完成并验证（见上，数据库不被创建） |
| 复用 AgentService | 完成，`BootCliRuntime` 取 `springAiAgentService` |
| 其余课程命令 | **待确认**（缺课程清单） |
| SessionManager | 由 `AgentService` + `AgentSessionStore` 承担创建与保存，未另起一套类；**课程是否要求独立 `SessionManager` 类待确认** |

### A3 Tool

| 条目 | 状态 |
|---|---|
| OryxTool / ToolRegistry / 输入 Schema 必填 | 完成，缺 Schema 或重名在注册时拒绝 |
| ToolResult | 复用已有 `ToolOutput`（字段相同），不另起第二套；课程命名差异待确认 |
| Profile `tools` 白名单过滤 | 完成：未列出的工具既不下发给模型也不能执行（抛 `SecurityException`） |
| File 读写 / HTTP GET / Shell | 完成，均经 Sandbox；默认拒绝 |
| MCP Client / Tool Adapter | **部分完成**：`McpClient`、`McpToolAdapter`、`McpToolLoader` 通过 `McpTransport` 抽象实现，用内存假服务测试；服务不可达只跳过不影响启动。**没有 stdio/HTTP 传输实现，也未接入 Spring 配置，未对真实 MCP 服务验证** |

### A4 Notify

| 条目 | 状态 |
|---|---|
| NotifyChannelAdapter / NotifyTarget / WebhookNotifyAdapter / NotifyTool | 完成 |
| 发送前 Sandbox 检查 | 完成：主机不在白名单时**不发出任何请求**（测试断言收到 0 条） |
| Webhook 失败不报告成功 | 完成：非 2xx、连接失败均为失败；5xx 标记可重试 |
| 模型不能指定 URL | 完成：只能选 Profile `notify_channels` 中的名字 |
| Mock HTTP Server 测试 | 完成（JDK `HttpServer`） |

### 关键设计与限制（必须知道）

1. **Sandbox 是应用层白名单，不是 OS 强隔离。** 路径经符号链接规范化后比较；Shell 只接受"裸程序名 + 参数数组"，不经过 shell；HTTP 不跟随重定向。需要强隔离请用容器/独立低权限用户。
2. 默认 Sandbox：仅 `.oryxos/workspace` 可读写，命令与主机均为空（全部拒绝）。通过 `oryxos.sandbox.*` 配置放开。
3. **Mock 模型约定**：消息 `tool:<名字> <json>`（或 `/tool ...`）会让 Mock 请求该工具，且只对已下发给模型的工具生效。Git Bash 会把 `/tool` 改写成 Windows 路径，请用 `tool:`。
4. 旧 Payment Lab（`/api/v1/labs/payment/*`）仍使用脚本模型 `PaymentScriptedGateway` 并保持 `@Primary`，作为离线回归入口。CLI 与 `/labs/spring-ai/payment` 使用 Spring AI 网关。
5. 注册的工具名由 Profile 的 `tools` 决定；当前只有 `payment-demo` 一个 Profile（旧 `.oryxos/profiles/*.yaml` 格式）。

### 无法验证 / 未完成

- **真实 LLM（DeepSeek）**：无 API Key，未验证。只验证了 Mock 与 Mockito 模拟的 `ChatModel`。
- **真实 MCP 服务**、stdio/HTTP 传输：未实现/未验证。
- **真实 Webhook 服务**（如钉钉/Slack）：只对本地 Mock HTTP Server 验证。
- P3C / Find Security Bugs：`COURSE_MAPPING.md` 记录的既有缺口，未处理。
- GitHub Actions CI 未在远端运行。
- Windows 下 `shell_exec` 测试依赖 `java` 在 PATH 中。
- Memory、Scheduler、REST 管理 API、Vue 管理台、AGENT.md 插件化：属于阶段 B/C，尚未开始。

### 实际修改的文件

新增（核心）：`core/agent/ContextLoader`、`core/sandbox/{Sandbox,SandboxViolation}`、`core/tool/{OryxTool,ToolContext,ToolRegistry}`

新增（tool）：`AllowListSandbox`、`SandboxPolicy`、`ToolArgs`、`GetPaymentTool`、`FileReadTool`、`FileWriteTool`、`HttpGetTool`、`ShellTool`、`RegistryToolExecutor`、`BuiltinTools`、`mcp/*`(4)、`notify/*`(4)

新增（cli/boot）：`cli/{CliRuntime,OryxCli}`、`boot/{BootCliRuntime,ToolConfiguration}`

修改：`PromptBuilder`、`ToolOutput`、`AuditedToolExecutor`、`DemoToolExecutor`（改为委托 `GetPaymentTool`）、`MockChatModel`、`ProfileLoader`（新增 `forListing`）、`OryxOsApplication`、`Lesson17RuntimeConfiguration`、`oryxos-tool/pom.xml`、`oryxos-cli/pom.xml`

新增测试：`ToolRegistryTest`、`PromptBuilderTest`、`AllowListSandboxTest`、`ControlledToolsTest`、`RegistryToolExecutorTest`、`NotifyTest`、`McpTest`、`MockChatModelTest`、`OryxCliTest`、`CliEndToEndTest`

未删除、未放宽任何已有测试。
