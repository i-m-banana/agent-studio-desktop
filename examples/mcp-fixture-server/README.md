# MCP 本机全链路测试 Server

这个样例只依赖 Node.js 标准库，面向 Agent Studio Desktop 的人工验收，不是生产 MCP Server。

在项目根目录另开终端：

```powershell
node examples/mcp-fixture-server/server.mjs
```

然后在“03 MCP 连接”填写：

- 显示名称：`本机 MCP 验收服务`
- Endpoint：`http://127.0.0.1:3001/mcp`
- Bearer Token 环境变量：留空

连接成功后会发现“查询项目里程碑”和“整理检查清单”。在 Agent Builder 编辑一个草稿，勾选其中一个 MCP 工具并发布新版本。建议系统提示词写明“用户询问项目阶段时必须调用查询项目里程碑工具”。

在对话台询问“请用工具查询第 6 阶段是什么”。预期过程：模型请求 MCP 工具；页面展示目标为 `MCP:本机 MCP 验收服务@127.0.0.1` 的高风险审批；批准后远端工具返回阶段信息；模型再生成最终回答；运行详情出现参数校验、审批、执行和完成审计。拒绝时远端工具不会执行，模型会收到拒绝结果。

当前样例实现协议版本 `2025-06-18` 的 initialize、initialized、tools/list、tools/call 和会话关闭所需最小消息，不实现 resources、prompts、sampling、server notification 或旧版 HTTP+SSE。
