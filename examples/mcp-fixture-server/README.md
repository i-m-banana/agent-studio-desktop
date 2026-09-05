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

连接成功后会发现两个工具、一个 Resource“Agent Studio MCP 验收说明”和一个 Prompt“生成验收计划”。Resource 可直接预览，也可导入当前选中的知识库；Prompt 填写 `module=MCP` 后会返回一条用户消息。在 Agent Builder 编辑一个草稿，勾选其中一个 MCP 工具并发布新版本。建议系统提示词写明“用户询问项目阶段时必须调用查询项目里程碑工具”。

在对话台询问“请用工具查询第 6 阶段是什么”。预期过程：模型请求 MCP 工具；页面展示目标为 `MCP:本机 MCP 验收服务@127.0.0.1` 的高风险审批；批准后远端工具返回阶段信息；模型再生成最终回答；运行详情出现参数校验、审批、执行和完成审计。拒绝时远端工具不会执行，模型会收到拒绝结果。

当前样例实现协议版本 `2025-06-18` 的 initialize、initialized、tools/list、tools/call、resources/list、resources/read、prompts/list、prompts/get 和会话关闭所需最小消息，不实现 sampling、elicitation、roots、resource templates、订阅通知、OAuth 或旧版 HTTP+SSE。

## stdio 模式

stdio Server 不需要先在终端常驻运行，平台会为发现和每次调用启动独立子进程。在 MCP 页面选择“本机 stdio”，填写：

- 启动程序：`node`
- 参数：`D:\idea_work\agent-studio-desktop\examples\mcp-fixture-server\stdio-server.mjs`
- 工作目录：可留空
- 环境变量映射：可留空

保存同步后应发现“查询本机项目状态”、Resource“MCP 集中验收说明”和 Prompt“生成 MCP 验收问题”。先预览 Resource，再把它导入当前知识库；Prompt 填 `module=MCP` 后应生成验收指令。把工具绑定到 Agent 并发布新版本，再询问“请使用工具查询 MCP 模块状态”。批准一次性操作后，结果应包含“本机 stdio MCP 调用正常”。

平台直接启动可执行程序，不经过 `cmd`、PowerShell 或 shell；配置中只保存参数数组。若 Server 需要密钥，环境变量映射应填写“子进程变量名=后端启动环境中的变量名”，数据库不保存密钥值。

## 建议一次性验收顺序

1. 分别建立 HTTP 和 stdio Server，确认同步结果显示 Tools/Resources/Prompts 数量，最近同步记录为 READY；
2. 预览两种传输的 Resource，并导入一个到已选择的知识库，确认文档状态 READY；
3. 运行 Prompt，填写必填参数，确认生成的角色和消息正文可读；
4. 绑定一个工具并发布 Agent，分别验证拒绝不执行、批准后执行和运行记录审计；
5. 导出一个 Server 配置，再导入前修改显示名称，确认文件没有密钥明文且新 Server 能重新同步；
6. 停用 Server，确认工具退出 Agent Builder；已被版本引用的 Server 删除请求应被阻止。
