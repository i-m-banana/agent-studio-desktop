# ADR 0010：MCP stdio 生命周期与本地信任策略

## 背景

Streamable HTTP 能连接常驻服务，但大量桌面 MCP Server 通过 stdio 由 Host 启动。stdio 不是“把 URL 换成命令”：客户端还要负责子进程、换行分隔 JSON-RPC、stderr、超时、取消和退出。如果直接拼接 shell 命令，会扩大为任意命令注入入口；如果 Server 删除后同时抹掉工具目录，又会破坏历史 AgentVersion 的解释性。

## 决策

- `McpTransportClient` 明确声明 transport，McpService 按 Server 配置选择 Streamable HTTP 或 STDIO；发现后的工具仍进入同一 ToolRegistry 和 SafeExecutionGateway。
- stdio 每次发现或调用启动一个短生命周期子进程，先 initialize，再发送 initialized，然后执行 tools/list 或 tools/call。消息使用 UTF-8、单行 JSON-RPC；stdout 出现非 JSON 内容视为协议错误，stderr 只作为有界诊断。
- 启动使用 `ProcessBuilder(List<String>)`，参数以数组保存，不经过 shell。拒绝把 cmd、PowerShell、bash 等解释器直接配置为启动程序。子进程不继承后端的全部环境，只保留启动所需的基础系统变量；密钥使用“子进程变量名 → 宿主变量名”显式映射，数据库不保存值。
- 超时发送 `notifications/cancelled`，随后关闭 stdin、等待退出、terminate，最后强制结束进程及后代进程。每次调用重新握手会增加延迟，但避免常驻进程泄漏和跨调用状态污染。
- Server 支持编辑、启停和删除。被 Agent 草稿或历史版本引用的 Server 禁止物理删除，只能停用。工具同步返回新增、下线和未变化数量；旧修订保留但不再 active。
- 新发现的工具仍默认 `EXECUTE/HIGH`。用户可以显式停用单个工具、调整超时，或确认信任后降为 LOW；平台不会根据远端 annotations 自动降低风险。

## 结果与限制

本机 stdio 和远程 HTTP 共用版本绑定、Schema 校验、审批、取消、运行步骤和审计。当前仍不是完整 MCP Host：没有 OAuth、resources、prompts、sampling、roots、elicitation、list_changed 订阅或长 session 池。stdio 进程由用户配置并以当前后端用户权限运行，尚无 OS 沙箱、资源配额或签名白名单，因此只适合用户明确信任的本地 Server。
