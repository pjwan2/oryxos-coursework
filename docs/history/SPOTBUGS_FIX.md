# Lesson 16 / Gate 2A — SpotBugs修复说明

## 现象

`mvn clean verify` 到 `oryxos-core` 报 `failed with 18 bugs and 0 errors`，后续模块 SKIPPED。此信息**不能直接证明全部18个 bug 的具体类型**：以本机 `target` 下的 SpotBugs XML 为准。

## 可能根因与代码修复

`AgentProfile` Java record 包含9个 `List/Map` 字段。SpotBugs 常对每一处提出构造时 `EI_EXPOSE_REP2` 和访问时 `EI_EXPOSE_REP`，正好18处。v1.3 为这些集合引入：

- 构造时制作不可变快照，避免调用者在注册后更改 Agent 配置；
- 对深层 YAML 列表/映射递归复制（防止嵌套结构被污染）；
- 显式访问器返回防御性副本，不直接返回内部可变引用；
- 新增 `AgentProfileImmutabilityTest` 回归保护。

同时调整 `OryxOsProviderProperties` 的 List getter/setter 与 `ProfileRegistry.all()`，避免下一模块重复出现引用暴露。

没有禁用 SpotBugs，没有降低 threshold，也没有加入全局 suppression。

## 在 Windows / Git Bash 执行

请先确保当前目录有父 `pom.xml`。停止另一个终端中的旧 `java -jar` 服务，以免后续启动端口冲突。然后：

```bash
mvn -B -ntp spotless:apply
mvn -B -ntp clean verify
```

如果继续报 SpotBugs，请定位实际报告：

```bash
find . -path '*/target/*' -iname '*spotbugs*.xml'
# 读取实际文件后，可查看其中的 BugInstance type / source line
```

## 自检边界

在此运行环境，已用 Java 21 `javac` 编译核心源码并运行独立的不可变性断言；**没有**完整 Maven / SpotBugs 环境，因此不能宣称这版 `mvn clean verify` 已通过。用户本机结果才是最终验收依据。
