# 第 4 周验收报告（发布版本）

Written for: 作业评阅者与项目所有者。本报告对**发布用的提交**重新执行了完整验收；此前对 `f31fd9f` 的报告原样保留为 [acceptance-report-baseline-f31fd9f.md](acceptance-report-baseline-f31fd9f.md)（历史基线，数字相同但不能代替本次验证）。

## 1. 被验收的版本

| 项 | 值 |
|---|---|
| 项目 | OryxOS Coursework — AI Agent Runtime（仓库 `oryxos-coursework`） |
| 分支 | `submission/week-04` |
| **验收提交** | `f11f6f046d7bfe96c12c76c443db72be630d6e41`（`chore(repo): prepare professional Week 04 coursework release`） |
| 验收环境 | 这个提交刚从原工作目录 `git clone` 出来的新目录 `oryxos-coursework`；验收前 `git status` 为空、没有远程、没有 `node_modules` 和 `static/admin`（目录里有一个 `target/`，是克隆后为格式化跑 `mvn -o spotless:apply` 留下的，`clean verify` 会先清除它） |
| 执行时间 | 2026-10-30 01:05:38 至 01:09:12（+11:00），Maven `clean verify` 用时 2 分 30 秒 |
| 工具 | Windows 10，JDK 21.0.2，Maven 3.9.16，Node v20.20.2，npm 10.8.2，Python 3.14 |
| 之后的提交 | 只增加本报告和 `evidence/final-release/` 以及重新生成的提交包副本，不含模块代码改动；核对：`git diff --stat f11f6f0 HEAD -- oryxos-core oryxos-provider oryxos-tool oryxos-memory oryxos-storage oryxos-scheduler oryxos-cli oryxos-web oryxos-boot` 应为空 |
| 阶段提交 | A `9c07dc0`、B1 `d57fcd2`、B2 `67e7364`、B3 `4c1e3cd`、B4 `e6c76c0`、周交付 `f31fd9f`…`6c1e643`、整理 `3c9bb86`、`c8b8513` |

原始输出在 [evidence/final-release/](evidence/final-release/)。

## 2. 命令与结果

| # | 命令 | 结果 | 证据 |
|---|---|---|---|
| 1 | `npm ci` | exit 0 | `01-npm-ci.txt` |
| 2 | `npm test` | **3 个文件、28 个测试全部通过** | `02-npm-test.txt` |
| 3 | `npm run build`（含 `check-build.mjs`） | exit 0；16 个文件、3 个资源引用，无外部 URL / source map / 疑似凭证 | `03-npm-build.txt` |
| 4 | `mvn -B -ntp spotless:apply` | exit 0，**未改动任何文件**（`git status` 为空） | `04-…txt`、`04b-…txt` |
| 5 | `mvn -B -ntp clean verify` | **BUILD SUCCESS**，九个模块 + 父工程全部 SUCCESS；没有 `-DskipTests`，没有关闭任何检查 | `05-mvn-clean-verify.txt` |
| 6 | `python scripts/test_summary.py` | **502 个测试，4 个跳过，0 个失败** | `test-summary.md` |
| 7 | Fat JAR 检查 | 85.7 MB；`Main-Class: …JarLauncher`，`Start-Class: io.oryxos.OryxOsApplication`；嵌套的 `oryxos-web` JAR 含 `static/admin/index.html` 等 18 个文件；八个 `oryxos-*` 模块 JAR 都在 | `06-jar.txt` |
| 8 | `java -jar …`（不带参数，工作目录=仓库根） | 启动成功；绑定 `127.0.0.1:8080`；health、`/admin/`、`/admin/scheduler`、Swagger UI、`/v3/api-docs`、Prometheus 全部 200；profiles 来自 `.oryxos/profiles`；一次记忆写入成功 | `13-default-start.txt` |
| 9 | `python scripts/week04_live_check.py --jar …`（真实 JAR、临时目录） | **77/77 通过**（Memory markdown 11 + sqlite 11、Sandbox 12、Web 29、Scheduler 14） | `07-live-check.txt` |
| 10 | 无头 Chrome 驱动六个管理页（真实 JAR，新库） | **27/27**；杀进程重启后 **5/5**；SQLite 内容重启前后逐字相同；13 张截图 | `vue-dashboard/` |
| 11 | `git diff --check` | exit 0 | `09-git-diff-check.txt` |
| 12 | `python scripts/check_doc_links.py` | 53 个 Markdown 文件，0 断链 | `08-doc-links.txt` |
| 13 | `python scripts/security_audit.py` | 当前文件树 **0 项待查**；历史中仅有旧文档里的本机路径，见第 5 节 | `10-security-audit.txt` |

静态检查（`verify` 阶段）：Checkstyle 0 违规（10 次运行）；SpotBugs 0 个 BugInstance 且 Error size 0（9 个模块）；Spotless check 通过（9 个模块）；PMD `check` 在 `failOnViolation=true` 下通过（30 次目标执行，构建成功即无违规）。四项检查均启用，没有排除规则。

## 3. 测试数量

| 模块 | 测试 | 跳过 | 失败 |
|---|---|---|---|
| oryxos-core | 33 | 0 | 0 |
| oryxos-provider | 37 | 0 | 0 |
| oryxos-tool | 106 | **4** | 0 |
| oryxos-memory | 80 | 0 | 0 |
| oryxos-storage | 29 | 0 | 0 |
| oryxos-scheduler | 42 | 0 | 0 |
| oryxos-cli | 9 | 0 | 0 |
| oryxos-web | 56 | 0 | 0 |
| oryxos-boot（surefire 105 + failsafe 5） | 110 | 0 | 0 |
| **合计** | **502** | **4** | **0**（通过 498） |

- 前端 vitest 另有 28 个。
- **4 个跳过**是 `PathSecurityTest` 里的符号链接测试（本机 Windows 账号没有创建符号链接的权限）；如实记录为跳过，没有被算作通过。同一条解析路径的 junction 版本测试执行并通过。
- 与 `f31fd9f` 基线逐类相同（含 `ArchitectureRulesTest` 8 个、`WebSmokeIT` 5 个）；这次是重新执行的结果，不是复制。

## 4. 逐项核对

| 要求 | 结果 | 证据 |
|---|---|---|
| Maven 九模块全部成功 | **通过** | `05-…txt` Reactor Summary |
| 测试没有被跳过或关闭 | **通过**（唯一的跳过是上述 4 个，原因已记录） | `05-…txt`、`test-summary.md` |
| PMD、SpotBugs、Checkstyle 仍启用 | **通过** | `05-…txt` |
| Fat JAR Manifest 正确 | **通过** | `06-jar.txt` |
| `java -jar` 可启动 | **通过** | `13-default-start.txt` |
| Health / Prometheus / Swagger | **通过** | `13-…txt`、`07-live-check.txt` |
| `/admin/` 可访问，子路由刷新不 404 | **通过** | `13-…txt`、`AdminUiTest`、`WebSmokeIT` |
| Vue 页面显示真实数据 | **通过（自动化）**：页面数据与 API、SQLite 逐项一致 | `vue-dashboard/main-run.txt`；**没有人工浏览器验收** |
| Memory / Sandbox / Scheduler | **通过** | `07-live-check.txt` |
| SQLite 重启恢复 | **通过** | `07-live-check.txt`（调度器杀进程重启）、`vue-dashboard/db-before/after.txt` 相同、`WebSmokeIT` |
| 第 4 周提交文件路径无误 | **通过**（链接检查 0 断链；`scripts/package_week04.py` 生成的两个 ZIP 通过扫描） | `08-doc-links.txt`，发布时的打包输出 |

## 5. 仓库安全审计与许可证检查

`scripts/security_audit.py` 扫描了 400 个受跟踪文件和全部 15 个提交（所有分支）的每一份补丁：

- **没有真实密钥、令牌、Webhook 地址、`.env`、数据库、真实 `MEMORY.md`、日志、`target/`、`node_modules/`、压缩包、PDF/Office 课件**进入过当前文件树或 Git 历史（文件名与内容两层检查）。
- 29 个"命中"是测试用的**假凭证和攻击样例**（如 `ghp_ABCDEFGH…`、`https://user:pw@host`、`api.example.com@evil.com`），它们正是测试用来证明"不会泄漏"的输入，已逐条人工复核。
- **本机路径**：当前文件树 0 处。历史里的若干旧文档提交（`3c9bb86`、`dfd6d68`、`a93f25a`、`f31fd9f`）曾写入过 `C:/Users/USER/...` 形式的本机目录路径（用户名为通用的 `USER`，不含密码或凭证）。**没有改写历史**，因为那会改变所有阶段提交 SHA 并使验收证据失效；风险评估为低。
- **个人信息**：提交者为 `pengju wang <wangpengju2008@gmail.com>`（15 个提交）。项目所有者已确认接受随历史公开。
- **第三方内容与许可证**：没有已知的第三方源码、模板或课程资料被复制进仓库（无法对早期由工具生成的阶段做逐行来源核查，故表述为"没有已知"）。**没有选择开源许可证**，也没有添加任何许可证文件；README 写明默认保留所有权利。依赖由 Maven/npm 按各自许可证获取；打包进构建产物的 Inter、JetBrains Mono 字体来自 `@fontsource`（SIL OFL 1.1），不在源码树中。
- 示例配置只使用占位值或环境变量；`config/env.example` 的密钥项为空。

## 6. 没有验证的东西

与 [known-limitations.md](known-limitations.md) 相同，最重要的：真实 LLM、MCP、Webhook、Mem0 从未运行；4 个符号链接测试跳过；无认证、非 OS 沙箱；管理台没有人工浏览器验收；503/504 只用合成异常验证；GitHub Actions 工作流没有在远程运行过（本报告成文时尚未推送）；老师的评分标准和「本周作业」页面的具体题目**未核实**。

## 7. 范围说明

本版本只整理命名、结构、文档、打包与安全检查，没有新增功能；没有第 5 周代码，也不存在第 5 周分支。
