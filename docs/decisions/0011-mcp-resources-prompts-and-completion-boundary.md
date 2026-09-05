# ADR 0011：MCP Resources、Prompts 与阶段完成边界

状态：已采用，2026-09-05。

## 背景

仅接入 tools 能让 Agent 调用外部能力，但 MCP Server 还会提供由应用控制的 Resources 和由用户主动选择的 Prompts。若只把 MCP 理解为“远程函数调用”，用户无法复用 Server 暴露的文档、上下文和提示模板，也无法判断一次同步到底发现了哪些能力。

## 决策

- HTTP 与 stdio 两种传输都协商 `2025-06-18`，按 Server capabilities 分别分页调用 `tools/list`、`resources/list` 和 `prompts/list`；不要求 Server 同时实现三者。
- Resources 和 Prompts 分别进入独立目录表。同步先下线旧目录，再按 URI 哈希或“名称、描述、参数”指纹写入新修订；读取前再次确认 Server 已启用且状态为 READY。
- Resource 由用户在 MCP 页面主动预览，或导入当前知识库并复用既有的 20 MB、Tika 解析、切块、embedding 和 pgvector 链路。多段纯文本会合并；混合或多段二进制明确拒绝。
- Prompt 由用户主动选择并填写参数，平台校验必填项后调用 `prompts/get`，展示 Server 返回的消息；它不会自动进入 Agent 系统提示词，也不会被模型暗中触发。
- 每次同步记录协议版本、三类目录数量、工具修订差异和失败原因。Server 配置可导入导出，但只包含 Bearer Token 或 stdio 环境变量的名称映射，不包含秘密值。
- 项目附带的 HTTP 与 stdio fixture 同时实现 tools、resources 和 prompts，协议测试真实覆盖两种传输。

## 安全与兼容边界

这次完成的是本地 Agent Studio 可用的 MCP 核心闭环，不等于实现整个 MCP 生态。HTTP 目前支持用户预先提供的 Bearer Token 环境变量，但不实现 OAuth 浏览器授权、PKCE、动态客户端注册和安全刷新令牌存储；stdio 按规范继续从环境获得凭据。Sampling、elicitation、roots、resource templates、subscriptions/list_changed 和长连接池也未实现，它们需要新的交互、安全或生命周期设计，不能伪装成已完成。

## 依据

- [MCP 2025-06-18 Server features](https://modelcontextprotocol.io/specification/2025-06-18/server)
- [MCP Resources](https://modelcontextprotocol.io/specification/2025-06-18/server/resources)
- [MCP Prompts](https://modelcontextprotocol.io/specification/2025-06-18/server/prompts)
- [MCP Authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)
