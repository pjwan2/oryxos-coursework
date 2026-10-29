# Gate 2D — 安装与验证（在同一个 v1.5 工程增量开发）

本增量安装脚本修正了上一份 Git patch 与 Spotless 自动格式化后源码无法匹配的问题。它只更改已有的三个 Java 文件，并新增三份 Java 文件、一份 profile 示例和本说明文件。原来的 `/api/v1/labs/payment/chat` 仍保留作为离线回归入口。

## 1. 应用增量

必须先在 `feature/spring-ai-react-integration` 开发分支，确保 `git status --short` 为空。把 `OryxOS_Gate2D_apply_to_existing_v15.py` 下载到 `~/Downloads`，在项目根目录执行：

```bash
python ~/Downloads/OryxOS_Gate2D_apply_to_existing_v15.py
git status --short
git diff --check
```

原始三个 Java 文件被备份在 Git 元数据目录 `.git/gate2d_before_install/`，且本地已有 Git 基线可以回退。本脚本不切换分支、不删除原项目和数据库。

## 2. 构建

```bash
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

不要使用 `-DskipTests`。若失败，请复制第一处编译或测试错误及 Reactor Summary，而不是重复解压项目。如果通过，再 `git add` 需要的文件并 `git commit`，不要提交密钥、数据库、编译输出或运行日志。

## 3. 运行旧接口确认不回退

请先确认 `jar` 的 manifest 有可执行入口；如果此前遇到 `no main manifest attribute`，先在根目录重新执行完整 `mvn clean package`。

```bash
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar --server.port=8097
```

另一个 Git Bash 终端：

```bash
curl -sS -X POST http://localhost:8097/api/v1/labs/payment/chat \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"userId":"regression1","content":"Look up P1001"}'
```

预期 Mock 演示仍输出 `P1001 PENDING 120 AUD`；该结果不证明真实模型通畅。

## 4. 真实模型（通过全部离线测试后）

复制 `examples/profiles/payment-real.yaml` 到 `.oryxos/profiles/payment-real.yaml`。使用环境变量配置自己的 `DEEPSEEK_API_KEY`，不要把密钥添加到 Git 或分享聊天；运行真实模型需要外网和有效密钥。

```bash
curl -sS -X POST http://localhost:8097/api/v1/labs/spring-ai/payment/chat \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"userId":"real1","content":"Please use get_payment to look up P1001 and tell me its status and amount."}'
```

此接口由 `SpringAiModelGateway` → `ReActLoop` → `ToolExecutor` → SQLite 审计处理。真实模型是否选择调用工具具有不确定性；通过请求返回之外，也须逐条检查调用 ID、入参、工具响应、Session 记录和审计表。继续保持本地实验环境，不在公网开放无鉴权接口。

## 验收边界

离线 Maven/JUnit/SpotBugs 绿灯才算 Gate 2D 的离线工程验收完成；老师完整第17节还包含 ContextLoader、更多 Session/权限场景等，不能混为已完成。
