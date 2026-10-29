# OryxOS 第 4 周作业提交材料

Written for: 极客时间 AI 编程实战营第 4 周作业的评阅者。本包只含说明、设计文档和验收证据；**源码**见 `source-reference.md`（另有独立的源码 ZIP，或以仓库地址提交，取决于课程平台要求，见下方"提交方式"）。

## 本周内容

| 课 | 主题 | 设计文档 |
|---|---|---|
| 21、22 | Memory：短/长期记忆；Markdown / SQLite / Mem0；核心与归档记忆 | `design/memory-design.md` |
| 23、24 | Sandbox：按 Profile 的权限交集；文件 / HTTP / Shell 防护；审计；SSRF | `design/sandbox-design.md` |
| 25 | 定时任务：cron、时区、防重叠、持久化、数据库迁移、重启恢复 | `design/scheduler-design.md` |
| 26（含 27、28 的相关部分） | REST API、统一错误格式、Swagger、Prometheus、Vue 3 管理台 | `design/web-service-design.md` |

## 本包文件

| 文件 | 内容 |
|---|---|
| `requirements-matrix.md` | 六节课：要求 → 代码 → 测试 → 运行证据 → 完成状态（含"等待核对"项） |
| `acceptance-report.md` | 实际执行的命令、测试数量（通过/失败/跳过）、运行证据、Git 提交 |
| `architecture.md` | 简明技术架构说明（一页） |
| `source-reference.md` | 对应的 Git 提交、关键源码位置、构建与复现步骤 |
| `known-limitations.md` | **未验证和未实现的东西**（真实 LLM / MCP / Webhook / Mem0、4 个跳过的符号链接测试、无认证、非 OS 沙箱） |
| `design/` | 四份设计文档（Memory、Sandbox、Scheduler、Web Service） |
| `evidence/` | 干净克隆上的完整构建与测试日志、测试汇总、真实 JAR 的 77 项运行检查、管理台的无头浏览器运行记录与截图 |

## 需要你先知道的几点

1. 全部验证使用本地 **Mock 模型**，没有真实 LLM、MCP、Webhook、Mem0 的运行证据。
2. 管理台与 REST API **没有认证**，只能在可信的本机使用。"仅限本机"的检查是过渡措施，不是安全方案。
3. Sandbox 是**应用层**白名单，不是操作系统沙箱；已写明它不能保证什么。
4. **4 个**符号链接测试在本机（Windows，无创建符号链接权限）被跳过，如实记录，不算通过。
5. 管理台由自动化的无头浏览器检查和单元测试验证，**没有人工浏览器验收**。
6. 老师的评分标准和提交规则不在仓库里，相关判断标为"等待核对"。

## 提交方式（等待核对）

仓库内没有课程平台的提交说明。可选做法（**由你决定，本次整理没有推送远程、没有登录平台、没有提交任何东西**）：

- 上传源码 ZIP（`deliverables/OryxOS-source-<提交>.zip`，由 `git archive` 生成，只含 Git 跟踪的文件）和本材料 ZIP（`deliverables/OryxOS-week04-materials.zip`）；或
- 把仓库推送到你自己的远程后提交地址（推送需要你的批准）。

## 如何复现

```bash
cd oryxos-web/src/main/frontend && npm ci && npm test && npm run build && cd ../../../..
mvn -B -ntp spotless:apply && mvn -B -ntp clean verify
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar        # http://127.0.0.1:8080/admin/
python scripts/week04_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar
```

需要 JDK 21、Maven 3.9+、Node ≥ 20.19、Python 3.9+（仅实机检查脚本）。不需要 API Key 或外部服务。
