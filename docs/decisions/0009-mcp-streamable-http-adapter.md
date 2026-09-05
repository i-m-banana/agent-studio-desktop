# ADR 0009：MCP Streamable HTTP 动态工具适配

日期：2026-09-05

## 背景

平台已有内置工具、显式 ReAct、版本绑定、审批、取消和安全审计，但工具仍由 Java 代码静态提供。MCP 的价值不是增加某一个工具，而是让用户在不修改后端代码的情况下连接外部能力。若 MCP 自建一条执行链，会绕过已有安全边界；若只记录远端工具名，不同 Server 会重名，远端描述或 Schema 更新也会使历史 AgentVersion 的模型输入发生漂移。

## 决定

- 第一版实现 MCP `2025-06-18` 的 Streamable HTTP 客户端，覆盖 initialize、initialized、tools/list 分页、tools/call、可选 session id、JSON 与 POST-SSE 响应、协议版本头和尽力关闭会话。
- `mcp_server` 只保存 Endpoint 和 Bearer Token 的环境变量名，不保存 Token 明文。普通 HTTP 只接受 localhost、127.0.0.1 或 ::1，远程 Endpoint 必须使用 HTTPS。
- 同步后把工具描述持久化到 `mcp_tool_catalog`。平台工具名由 Server id、清洗后的远端名称和描述指纹组成，既避免跨 Server 重名，也使描述、Schema 改变时生成新修订；旧 AgentVersion 继续引用旧修订。
- MCP Server 的声明属于外部输入。首版所有 MCP 工具统一标记 `EXECUTE/HIGH`，不能依据 `readOnlyHint` 自动降级。执行前展示目标 `MCP:<本地配置名>@<host>` 并进行参数绑定的一次性审批。
- 动态工具进入既有 ToolRegistry，随后完整经过 ToolInputValidator、SafeExecutionGateway、RunStep、运行取消/超时和 AuditEvent；MCP 适配器无权绕开网关。
- 工具返回的 text content 会合并，其他 content 和 structuredContent 序列化后回传；适配器限制结果为 32,000 字符，运行步骤和模型 Observation 继续使用平台现有的 4,000 字符上限。

## 后果

用户可以通过网页完成“保存 Server—连接测试—发现工具—绑定草稿—发布版本—模型调用—审批—审计”的闭环。连接或同步失败会在 Server 记录中保留最后错误，已从远端移除的工具不会再供新草稿选择。

这不是完整 MCP Host。当前不支持 stdio、旧 HTTP+SSE、OAuth、resources、prompts、sampling、roots、elicitation、工具列表变化通知和长连接复用。每次 tools/call 会建立一个短生命周期 MCP session，兼容性清晰但有额外握手开销。HTTP 认证仅支持从环境变量读取 Bearer Token；生产级 OAuth 和细粒度信任策略后置。

## 规范依据

- [MCP 2025-06-18 生命周期](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)
- [MCP 2025-06-18 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- [MCP 2025-06-18 工具协议](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
