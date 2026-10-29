# 第 4 周作业说明

Written for: 课程老师与复核者（第 4 周作业：Memory、Sandbox、定时任务、Web Service 与管理平台）。设计文档为英文（含大量代码标识符），对照矩阵、验收报告和限制清单为中文。

## 这是什么

OryxOS 是课程项目：一个 Java 21 / Spring Boot 3.5 / Spring AI 的 Agent 运行时，九个 Maven 模块。本周完成并交付：

| 课 | 内容 | 设计文档 |
|---|---|---|
| 21、22 | Memory：短/长期记忆、Markdown / SQLite / Mem0 三后端、核心与归档记忆 | [memory-design.md](memory-design.md) |
| 23、24 | Sandbox：按 Profile 的权限交集、文件 / HTTP / Shell 防护、审计、SSRF | [sandbox-design.md](sandbox-design.md) |
| 25 | 定时任务：cron、时区、防重叠、持久化、迁移、重启恢复 | [scheduler-design.md](scheduler-design.md) |
| 26（含 27 的会话列表、28 的 Scheduler 界面） | REST API、统一错误格式、Swagger、Prometheus、Vue 3 管理台 | [web-service-design.md](web-service-design.md) |

## 先读哪些文件

1. [requirements-matrix.md](requirements-matrix.md)：六节课的"要求 → 代码 → 测试 → 证据 → 状态"。
2. [acceptance-report.md](acceptance-report.md)：实际执行的命令、测试数量、通过/失败/跳过、Git 提交。
3. [known-limitations.md](known-limitations.md)：**没有验证或没有实现的东西**（真实 LLM / MCP / Webhook / Mem0、4 个符号链接测试、无认证）。
4. 架构：[overview](../../architecture/overview.md)、[module-boundaries](../../architecture/module-boundaries.md)、[design-decisions](../../decisions/design-decisions.md)。

## 怎么在干净环境里运行

需要：JDK 21、Maven 3.9+、Node ≥ 20.19 与 npm（前端）、Python 3.9+（只用于可选的实机检查脚本）。**不需要任何 API Key、数据库或网络服务**（默认使用本地 Mock 模型）。

```bash
git clone <仓库>  &&  cd oryxos-coursework                # 或解压源码包
cd oryxos-web/src/main/frontend
npm ci && npm test && npm run build     # 前端构建进 oryxos-web/src/main/resources/static/admin/
cd ../../../..
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify                # 含 Checkstyle/PMD/SpotBugs、全部测试、真实 JAR 冒烟测试
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar
```

然后（默认端口 8080，只绑 127.0.0.1）：

| 地址 | 内容 |
|---|---|
| <http://127.0.0.1:8080/admin/> | 管理台（Sessions / Profiles / Tools / Memory / System / Scheduler） |
| <http://127.0.0.1:8080/api/v1/health> | 健康检查 |
| <http://127.0.0.1:8080/swagger-ui.html> | OpenAPI 界面 |
| <http://127.0.0.1:8080/actuator/prometheus> | 指标 |

演示 Agent 在 `.oryxos/profiles/`（`payment-demo`、`memory-demo`）；带沙箱和定时任务的示例在 `examples/profiles/`（复制到 profile 目录后重启）。试一下：

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/agents/memory-demo/invoke -H "Content-Type: application/json" \
  -d '{"message":"tool:save_memory {\"content\":\"met Sam at the harbour\"}"}'
```

（`tool:<name> <json>` 是 Mock 模型的"调用工具"约定；真实模型需要配置 `DEEPSEEK_API_KEY`，本次交付没有验证过。）

一键复现实机检查（启动真实 JAR，使用临时目录，约 1 分钟）：

```bash
python scripts/week04_live_check.py --jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar
```

## 目录

```
docs/architecture/            架构总览、模块边界、设计决策、工作区整理
docs/coursework/week-04/      本周设计文档、矩阵、验收报告、限制、evidence/
docs/course-progress.md       各阶段（A、B1～B4）的完整过程记录
deliverables/week-04/         提交包的内容（由 scripts/package_week04.py 打包为 zip）
scripts/                      build-all.sh、week04_live_check.py、test_summary.py、package_week04.py
```

## 重要提醒

- 管理台和 API **没有认证**，只能在可信的本机使用；不要暴露到公网。
- 本地 Git 提交不等于老师能访问源码。是否推送、如何提交由你决定，本次整理**没有推送任何远程，也没有登录课程平台**。
