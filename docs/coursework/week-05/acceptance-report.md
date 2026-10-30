# 第 5 周验收报告（C1 + C2：第 27、28 节）

Written for: 项目所有者与作业评阅者。**第 5 周尚未完成**（第 29～32 节还没做，C3/C4 是下一阶段）；本报告只覆盖第 27、28 节的工程集成与验收。数字全部来自本机实际执行；Mock 验证与真实外部服务验证分开写。

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
