# 第 17 节：Phase 2 Session + Audit 实战 v1.5

## 状态与边界

从 v1.4 派生的独立工程。增加了：`AgentService`（统一调用入口）、`ProfileContext` try/finally、`AgentSessionStore` / SQLite JPA 实现、`AuditedToolExecutor`、`tool_invocations` 审计、可调用本地 mock 的 Payment Lab API。

**注意：本阶段 HTTP 演示使用确定性脚本模型 `PaymentScriptedGateway`，并没有把 Spring AI `ProviderService` 的 ChatResponse ToolCall 结构真正转换成 ReAct 使用的 ModelTurn。没有真实 LLM 参与。** 正式生产级 Sandbox、ContextLoader、真实 Spring AI 工具调用、Picocli CLI 还未完成，不应标记整个第 17 节或第 20 节作业通过。

## 操作（Git Bash, 工程根目录）

```bash
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
java -Dfile.encoding=UTF-8 -cp "oryxos-core/target/classes;oryxos-tool/target/classes;oryxos-cli/target/classes" io.oryxos.cli.ReActLabMain P9999
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar --server.port=8085
```

不要忽略 Maven test、SpotBugs 或 PMD。所有命令从含父 pom.xml 的工程根目录执行。

启动后另开 Git Bash：

```bash
# Windows Git Bash 的 curl 会用本地代码页(GBK)发送中文，服务端会返回 400 "Malformed request body"。
# 请用 \u 转义（下面写法）或 UTF-8 文件 + --data-binary @file。
curl -s -X POST 'http://localhost:8085/api/v1/labs/payment/chat' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"userId":"student1","content":"查询支付单 P1001"}'
curl -s 'http://localhost:8085/api/v1/labs/payment/sessions/student1'
```

也可 `P9999`：必须返回 `查询失败`、tool_invocations 中 success=false。

查询 SQLite 审计（本地可选择使用 Python）。**必须在启动应用的同一目录（工程根目录）执行**：`./oryxos-dev.db` 是相对路径，换目录会打开另一个空库（`Tables: []`）：

```bash
python - <<'PYSQL'
import sqlite3
with sqlite3.connect('oryxos-dev.db') as conn:
    for row in conn.execute('select session_id,tool_name,success,error_category from tool_invocations order by id desc limit 5'):
        print(row)
PYSQL
```

## 新增测试

- `AgentServiceTest`：正常/异常均清除 ProfileContext，失败仍保存部分历史。
- `AuditedToolExecutorTest`：成功、业务失败、越权拒绝各一笔审计。
- `PaymentLabIntegrationTest`：Spring 容器下，SQLite 的 Session 与工具审计持久化（需要依赖与数据库可用）。

## 已知限制

1. 没有 Spring AI `ProviderService` ↔ `ModelGateway` 的正式响应/ToolCall 桥接。
2. SQLite 仅用于本地单进程实验，不保证并发更新同一个 Session 不丢消息；后续要加乐观锁或串行化。
3. `AgentService` 的会话持久化与模型调用没有单事务，避免包住外部 I/O，但失败中途的部分历史要明确可接受。
4. 本地 Lab API 没有认证，仅监听 127.0.0.1。不得暴露公网或生产环境。
5. 真实工具必须经过 Sandbox 策略检查；目前只有只读演示工具与 Profile allowlist。
