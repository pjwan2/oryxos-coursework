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
| 5 | 27～32：主流程、稳定性、插件化、动态管理、Demo、复盘 | 未开始 | — | — | — |

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

未开始。范围和前置事项见 [week-05/README.md](week-05/README.md)。开始时从 `submission/week-04`（标签 `week-04`）切出 `feature/week-05-...`，不要在 `submission/week-04` 上继续开发。

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
