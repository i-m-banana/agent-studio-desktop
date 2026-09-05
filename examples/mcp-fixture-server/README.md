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

## stdio 模式

stdio Server 不需要先在终端常驻运行，平台会为发现和每次调用启动独立子进程。在 MCP 页面选择“本机 stdio”，填写：

- 启动程序：`node`
- 参数：`D:\idea_work\agent-studio-desktop\examples\mcp-fixture-server\stdio-server.mjs`
- 工作目录：可留空
- 环境变量映射：可留空

保存同步后应发现“查询本机项目状态”。把它绑定到 Agent 并发布新版本，再询问“请使用工具查询 MCP 模块状态”。批准一次性操作后，结果应包含“本机 stdio MCP 调用正常”。

平台直接启动可执行程序，不经过 `cmd`、PowerShell 或 shell；配置中只保存参数数组。若 Server 需要密钥，环境变量映射应填写“子进程变量名=后端启动环境中的变量名”，数据库不保存密钥值。
