# 1.0.0-rc1 成熟开发锚点

锚点日期：2026-09-07。

本文件用于后续开发时快速判断哪些行为属于已经建立的基线，防止新功能破坏核心语义。完整面试与项目知识见 `../examples/project-knowledge-pack/16-成熟版本开发锚点与面试全景.md`，后续路线见 `../examples/project-knowledge-pack/17-锚点后的未来开发计划.md`。

## 必须保持的基线不变量

1. AgentDefinition 可编辑，AgentVersion 内容快照不可变；会话始终绑定精确版本。
2. 归档只阻止新会话，不破坏历史运行；有 Conversation/AgentRun 引用的版本不得硬删除。
3. MySQL 管业务事实，pgvector 管可重建索引，本地 data 管原始文件和用户绑定密文。
4. 密钥值不得进入业务数据库、日志、配置导出、RunStep、AuditEvent 或 API 响应；环境变量优先于 DPAPI。
5. 所有 BUILTIN/MCP/未来 SSH/Coding 工具必须经过同一个 ToolRegistry 和 SafeExecutionGateway。
6. HIGH 工具必须对具体参数进行一次性、有限期审批；批准不能变成永久授权。
7. RunStep 记录运行语义，AuditEvent 记录安全决策；两者不能混为一张无边界日志表。
8. 取消、超时和正常完成必须依赖原子状态迁移，普通完成不能覆盖取消请求。
9. RAG 只能检索版本绑定的知识范围，并向用户展示来源；没有候选时确定性拒答。
10. MCP Resource 与 Prompt 不得自动执行：Resource 由应用/用户导入，Prompt 由用户主动取用。
11. 外部 MCP 自报的只读或安全 annotations 不能自动降低平台风险等级。
12. 新能力必须记录自动化证据、真实验收状态与未实现边界，不能把规划写成完成。

## 锚点证据

- Java 21 + Spring Boot 3.4.3，React 19 + TypeScript + Vite；
- 118 个后端主 Java 源文件、19 个测试源文件；
- Flyway V1–V10；
- 后端 31 个测试全部通过；
- 前端生产构建通过；
- DeepSeek、Ollama Qwen、MySQL、pgvector、内置审批、MCP HTTP/stdio、Tavily 均有真实或协议级证据；
- Windows DPAPI 有真实加密往返测试；
- 一键启动/停止、发布检查、备份/受保护恢复脚本已建立。

## 正式代码锚点尚缺的一步

当前文档是知识锚点。要形成可回退的代码锚点，仍须完成日常 MySQL V10、DPAPI 重启和版本生命周期人工验收，然后审阅并提交当前工作区、创建 `1.0.0` Git 标签。未完成这些动作前，不应把本文当作已经存在的 Git tag。
