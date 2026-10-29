# 简明技术架构

完整版本见源码仓库 `docs/architecture/`（overview、module-boundaries、design-decisions，英文）。这里是一页摘要。

## 一个引擎，三个入口

```
  CLI (Picocli)        REST 控制器 (Spring MVC)        定时任务 (Spring TaskScheduler)
        \                      |                              /
         +--------->  AgentService.process(channel, userId, profile, message)  <--------+
                      （core 模块；唯一的编排者，Bean 名 springAiAgentService）
                                   |
                      会话 id = channel:userId:profile （SessionIds）
                                   |
                               ReActLoop
                      /                          \
          ModelGateway → ProviderService      AgentToolExecutor
          (Spring AI ChatModel,                AuditedToolExecutor → RegistryToolExecutor
           工具自动执行关闭)                         → ToolRegistry（Profile 白名单）
                                                  → OryxTool（先过 Sandbox，再做事）
```

三个入口只负责把自己的输入变成 `(channel, userId, profile, message)`：CLI 为 `cli`，REST 为 `web` / `api`，调度器为 `scheduler`。所以同一个会话可以从任何入口继续，调度器的运行也出现在普通会话列表里。

## 九个模块与依赖方向

| 模块 | 职责 | 依赖 |
|---|---|---|
| core | 领域模型与端口（Agent 编排、会话、工具/记忆/沙箱/调度契约）；只导入 JDK | — |
| provider | 把 Profile 的 provider 名变成模型调用；YAML Profile 加载；失败分类 | core |
| tool | 内置工具、沙箱实现、**唯一执行工具的代码**、审计包装、MCP、通知 | core |
| memory | Markdown / SQLite / Mem0 记忆后端、save/recall 工具、提示词加载器 | core |
| storage | 全部关系型持久化：会话、LLM/工具审计、调度存储、Schema 与迁移 | core, provider |
| scheduler | cron 触发、时区、防重叠、运行记录；只通过 AgentService 执行 | core |
| cli | Picocli 命令行 | core, tool |
| web | REST 控制器、统一信封与异常映射、本地访问限制、托管 Vue 管理台 | core |
| boot | 组合根：Spring Boot 应用、配置类、集成测试 | 全部 |

所有箭头指向 core，无环。规则由 `ArchitectureRulesTest`（8 个测试）在每次构建中检查：模块集合与依赖图、core 只导入 JDK、数据库 API 只在 storage（有一处文档化的例外）、web 不能触达执行与基础设施、provider/scheduler 不能直接执行工具、只有一个 ToolRegistry / 会话存储 / 工具审计写入者、每个工具执行器都被审计包装。

## 权限、审计、错误

- **权限**：工具要执行，必须 (1) 在 Profile 的工具白名单里，(2) 该 Profile 推导出的沙箱允许这个具体动作。有效权限 = Profile 声明 ∩ 运维上限；未声明 = 全部拒绝；通过 `ToolContext` 显式传入，没有 ThreadLocal。
- **审计**：每次工具调用（成功、失败、被拒）和每次模型调用都是一行数据库记录；错误文本经脱敏（不含 URL 路径、令牌，≤200 字符）。
- **错误**：REST 统一信封 `{code, message, data, timestamp}`；500 固定文本，不返回内部异常或 Provider 消息；模型不可达 503、超时 504。
- **可观测**：每个响应带 `X-Trace-Id`；`/actuator/prometheus`；OpenAPI `/v3/api-docs`。
- **本地限制**：服务绑定 127.0.0.1；敏感接口还要求回环对端、无转发头、本地 Origin 和 Host。**这不是认证。**

## 持久化

SQLite 单文件：`sessions`、`llm_calls`、`tool_invocations`、`memory_entries`、`scheduled_tasks`、`task_executions`、`schema_migrations`。Schema 由 storage 独占（新库用 `schema.sql`，旧库用带校验和、备份与演练的版本化迁移）；Hibernate 不改表结构。

## 管理台

Vue 3 + Vite，源码 `oryxos-web/src/main/frontend/`，构建进 `static/admin/`，由同一个 Spring Boot JAR 在 `/admin/` 提供；只调用公开 REST API。六页：Sessions、Profiles、Tools、Memory、System（只读）和 Scheduler（列表、历史、立即执行、启停；把"技术执行状态"与"工具审计结果"分开显示，沙箱拒绝不会显示为成功）。

## 已知的两处结构偏差

1. `SqliteMemoryStore` 在 memory 模块而非 storage（通过纯 `DataSource` 访问，表和迁移归 storage）。
2. `storage` 依赖 `provider`（模型调用审计端口在 provider 模块）。

另有一个历史遗留的第二个 `AgentService` Bean（第 17 节实验用，脚本化模型），共用同一个会话存储和审计执行器。详见 `design/` 与源码仓库的 `design-decisions.md`（D-08）。
