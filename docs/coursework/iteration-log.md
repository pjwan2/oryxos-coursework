# 迭代记录（每周一次迭代）

每个课程周是一次迭代：从一个明确的目标开始，以一个可复现的验收和一个 Git 标签结束。本页是总索引；每周的详细过程、命令和证据在各周目录和 [course-progress.md](../course-progress.md) 里。**只记录实际发生并验证过的事；没做的、没验证的单独列出。**

## 约定

| 项 | 规则 |
|---|---|
| 开发分支 | `feature/<主题>`（例如 `feature/week-05-main-flow`）；整理类用 `chore/`，缺陷用 `fix/` |
| 提交分支 | `submission/week-NN`：只放该周已验收的内容，**不混入下一周未验收的代码** |
| 标签 | `week-NN`：指向该周最终提交，一周一个，不移动 |
| 验收 | 对该周提交的干净克隆执行 `npm ci`、`npm test`、`npm run build`、`mvn -B -ntp spotless:apply`、`mvn -B -ntp clean verify`、`git diff --check`；结果写进该周的 `acceptance-report.md`，测试数字必须来自本次执行 |
| 每周文件 | `docs/coursework/week-NN/`：README、需求矩阵、设计、验收报告、已知限制、`evidence/` |
| 发布与提交 | GitHub 推送和课程平台提交是两步，分别记录状态 |
| 周末更新 | 在本页追加该周一节（用下面的模板），再打标签 |

## 汇总

| 周 | 课 | 状态 | 测试（通过 / 跳过 / 失败） | 提交分支 | 标签 → 提交 |
|---|---|---|---|---|---|
| 3 | 15～20：骨架、Provider、ReAct、CLI、Notify、Tool | 完成（阶段 A） | 106 个，0 失败 | （无独立提交分支，见 `master` 之后的线性历史） | `week-03` → `9c07dc0` |
| 4 | 21～26：Memory、Sandbox、定时任务、Web Service 与管理台 | 完成并已发布到 GitHub；**课程平台尚未提交** | 502 个：498 / 4 / 0 | `submission/week-04` | `week-04` → 该分支发布时的最终提交（见标签本身） |
| 5 | 27～32：主流程、稳定性、插件化、动态管理、Demo、复盘 | 完成并已发布到 GitHub（Mock 验证的课程快照）；**课程平台尚未提交（Pending）**；真实 LLM / Webhook / 48 小时未验证 | 789 个：783 / 6 / 0 | `submission/week-05` | `week-05` → 该分支发布时的最终提交（见标签本身） |

## 第 3 周（课 15～20）

- **目标**：把课程骨架做成能跑的 Agent：Provider 路由、ReAct 循环、会话与工具审计、Picocli CLI、Notify、Tool 注册与 MCP 客户端。
- **提交**：`9c07dc0 Phase A (weeks 3)`（之前的 `f8fd004`、`c5ce102`、`cf9c62c` 是进入本阶段的基线）。
- **验收**：当时 106 个测试通过；详见 [course-progress.md](../course-progress.md) 阶段 A。
- **做完了**：Mock 模型下的 ReAct、会话与工具审计、CLI、通知和工具抽象。
- **没验证**：真实 LLM、真实 MCP、真实 Webhook。
- **文档**：[week-03/README.md](week-03/README.md)。

## 第 4 周（课 21～26）

- **目标**：Memory（三后端）、Sandbox（按 Profile）、定时任务（含 SQLite 迁移）、REST API 与 Vue 管理台。
- **迭代过程**（每个阶段一个提交，测试数逐步增加）：

| 阶段 | 提交 | 内容 | 测试 |
|---|---|---|---|
| B1 Memory | `d57fcd2` | Markdown / SQLite / Mem0 后端、同一契约测试、save/recall 工具 | 207 |
| B2 Sandbox | `67e7364` | Profile 权限交集、文件 / HTTP / Shell 防护、拒绝审计 | 310（4 跳过） |
| B3 Scheduler | `4c1e3cd` | 版本化 SQLite 迁移、cron + 时区 + 防重叠、重启恢复、调度 API | 427 |
| B4 Web | `e6c76c0` | 六类 REST 控制器、统一错误、Vue 3 管理台、真实 JAR 冒烟测试 | 494 |
| 交付整理 | `f31fd9f`…`6c1e643` | 架构守卫测试（8 个）、设计文档、需求矩阵、实机检查脚本（77 项） | 502 |
| 仓库整理 | `3c9bb86`、`c8b8513` | 文档分层、CI 修复（先构建前端）、运行手册 | 502 |
| 发布 | `f11f6f0`…`a79f944` | 命名整理、README、安全审计、最终验收、GitHub 发布与文档收口 | 502 |

- **最终验收**（`f11f6f0`，干净克隆）：502 个测试、498 通过、4 跳过、0 失败；前端 28 个；真实 JAR 77/77；GitHub Actions（Ubuntu）通过。见 [week-04/acceptance-report.md](week-04/acceptance-report.md)。
- **没验证**：真实 LLM、MCP、Webhook、Mem0；4 个符号链接测试在 Windows 本机跳过；管理台没有人工浏览器验收；无认证。见 [week-04/known-limitations.md](week-04/known-limitations.md)。
- **发布状态**：GitHub 已发布 <https://github.com/pjwan2/oryxos-coursework/tree/submission/week-04>；课程平台提交框**尚未提交**（提交后在此处更新日期）。
- **文档**：[week-04/README.md](week-04/README.md)。

## 第 5 周（课 27～32）

- **分支**：开发在 `feature/week-05-agent-lifecycle`（从标签 `week-04` 创建，基线 `4dc96af`）；提交分支 `submission/week-05`，标签 `week-05`（CI 全绿后创建）。
- **目标**：第 27 节主流程集成、第 28 节稳定性与多 Agent、第 29 节 `AGENT.md` 插件化、第 30 节动态管理、第 31 节三个业务 Demo、第 32 节总结与发布。
- **迭代过程**：

| 阶段 | 提交 | 内容 | 测试 |
|---|---|---|---|
| C1 第 27 节 | `178411b` | 假模型服务器 + 真实 Spring AI 客户端的端到端测试（天气：2 次模型调用/1 次工具调用；记忆；三条失败路径）、进程内 Mock 流程测试、CLI/REST 同库对账；**修复** Provider 不可达时请求线程被默认重试挂住数分钟 | 517 |
| C2 第 28 节 | `49777b7` | 调度全链路、不重叠（6 并发只执行 1 次）、重启恢复逐行对账、多 Agent 隔离、新增只读 `outcome` 端点（技术成功 ≠ 业务成功）、真实 JAR 实机检查 31 项 | 538 |
| 文档 | `a111ee8` | 第 5 周需求矩阵、验收报告、限制、证据 | 538 |
| C3 第 29 节 | （见提交 `feat(week05): support declarative AGENT.md plugins`） | `AGENT.md` 目录即 Agent：`AgentLoader`/`AgentDirectoryScanner`/`AgentStartup`；可变线程安全 `ProfileRegistry`；调度器动态注册；沙箱缓存失效且每次工具调用按最新定义判断；渐进式上下文；真实 JAR 22/22 | 616（611/5/0） |
| C4 第 30 节 | （见提交 `feat(week05): add dynamic agent lifecycle and admin management`） | 统一生命周期 CRUD + 归档、`WorkspaceWatcher`、每 Agent 私有记忆（V4 迁移）、调度 `execution_id` 精确对账、会话串行、AI 草稿→确认保存、受限工作区 API、管理台 Agent 页（五个标签）、Scheduler 页改读 outcome；真实 JAR 96/96 | 728（722/6/0） |
| C5 第 31 节 | （见 `feat(week05)` 的天气 / 科技日报 / GitHub 三个提交与 `test(week05): validate three agent demos end-to-end`） | 三个业务 Agent 示例（`examples/agents/`）、受信 Python 脚本、真实 Cron 触发观察、可控时钟的跨天验证、管理台“Scheduled runs”、outcome `calls` 明细、运行手册；Tier A 真实 JAR 64/64；Tier B：Open-Meteo / HN Search / GitHub Search 真实只读访问成功，真实 LLM 与 Webhook 未验证 | 786（780/6/0） |
| C6 第 32 节 | （见 `docs(week05)` 和 `chore(release)` 提交） | 第一阶段总结、发布前检查（Quick Start 检查脚本、示例私有网络放行的 `LOCAL DEMO ONLY` 标记与守护测试、安全扫描）、干净克隆全量验收、GitHub 发布 | 789（783/6/0） |

- **最终验收**（干净克隆）：789 个测试、783 通过、6 跳过（符号链接）、0 失败；前端 42 个；真实 JAR：三个 Demo 64/64、第 29/30 节 96/96、第 27/28 节 31/31、第 4 周回归 77/77、Quick Start 56/56；Tier B：Open-Meteo / HN Search / GitHub Search 真实只读访问成功。见 [week-05/acceptance-report.md](week-05/acceptance-report.md)。
- **没验证 / 没实现**：真实 LLM（含 AI 生成）、真实 Webhook、MCP、Mem0、真实 48 小时连续运行、Linux/macOS 实际运行未验证（Open-Meteo / HN Search / GitHub Search 的只读访问已验证）；`HumanTriggerFlowIT` 未实现；旧 `profiles/*.yaml` Agent 仍共享全局记忆（`AGENT.md` Agent 已有私有记忆，C4）；管理 API 仍无认证；管理台无人工浏览器验收。
- **第 29/30 节前的问题**：Agent 定义的单一来源、可变 Profile 目录、沙箱缓存驱逐、per-agent memory、工具审计执行 ID、会话并发、管理 API 边界——已在 C3/C4 解决（见 [week-05/known-limitations.md](week-05/known-limitations.md) F、G 节）。
- **发布状态**：GitHub 已发布 <https://github.com/pjwan2/oryxos-coursework/tree/submission/week-05>（标签 `week-05`，推送与 CI 通过后）；课程平台提交框**尚未提交（Pending）**，提交后在此处更新日期。未创建 `v0.1.0`。
- **下周前置事项**：Week 06 进入 When 分布式延迟消息系统；OryxOS 暂停新功能。若要继续 OryxOS：真实 LLM 一次有限验证、认证、脚本隔离、真实 Webhook 与 48 小时观察。
- **文档**：[week-05/README.md](week-05/README.md)。

## 每周追加的模板

```
## 第 N 周（课 X～Y）
- 目标：
- 迭代过程：（阶段 / 提交 / 内容 / 测试数）
- 最终验收：（提交 SHA、命令、通过/跳过/失败、真实运行证据）
- 没验证 / 没实现：
- 发布状态：GitHub（分支、标签、URL）；课程平台（已提交 / 尚未提交，日期）
- 下周前置事项：
```
