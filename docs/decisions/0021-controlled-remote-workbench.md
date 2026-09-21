# ADR 0021：受控远程工作台与审批目标二次绑定

## 状态

已接受，自动化验证及真实界面/SSH 工作台人工验收通过。

## 背景

Agent Studio 已具备 SSH/SFTP 只读、摘要绑定文本补丁和五种固定 SSH Exec 任务，但能力主要通过对话测试台暴露。`walicode` 与 `walissh` 提供了更接近开发工具的文件树、标签页、任务输出和右侧助手体验。同时，现有 HIGH 审批虽然保存 `targetEnvironment`，批准后只重新校验参数摘要；用户在等待审批期间改变 SSH 配置时，旧审批可能被用于新目标。

## 决策

新增“远程工作台”一级页面，采用左侧远程资源、中间文件/任务/输出标签页、右侧 Agent/审批/步骤的三栏结构。

人工点击目录或文本文件时，Web UI 通过专用只读浏览 API 直接复用 `ListRemoteDirectoryTool` 和 `ReadRemoteTextFileTool`。该通道只接受相对 path、行数和条目数，不提供搜索、补丁、写入或执行；连接仍使用固定主机指纹、受保护凭据、RemotePathPolicy、逐级 `lstat`、符号链接拒绝、受保护路径过滤、UTF-8/文件大小/输出边界。它不会创建 AgentRun、调用模型或要求 AgentVersion。

Agent 发起的读取仍继续经过 ToolRegistry、SafeExecutionGateway、RunStep 与 AuditEvent。所有补丁和固定任务也保持原主链；人工只读浏览 API 不能被包装成 AgentTool、MCP Server 或任意文件接口。

工作台中的任务卡只生成固定任务意图。最终工具 Schema 只接受枚举 task 和受限相对 path；控制台只显示有界结果，不是 PTY，不能编辑或粘贴 Shell。

SafeExecutionGateway 在 HIGH 审批通过后、工具启动前重新解析当前目标，并与 ApprovalRequest 中保存的目标逐字比较。不一致时记录 `TOOL_TARGET_CHANGED/REJECTED`，且不产生 `TOOL_EXECUTION_STARTED`。SSH 审批目标包含用户名、主机、端口、远程根目录和固定 SHA-256 主机指纹。

## 后果

- 连接配置在审批等待期间变化时，必须重新发起工具调用和审批；
- 工作台复用现有对话运行状态，切换 AgentVersion 会清空当前会话上下文；
- 文件树和文件预览不再等待模型；它们是明确的人工只读操作，不产生 AgentRun、RunStep 或模型费用；
- 右侧只突出当前 RunStep 和最近六条消息，完整历史保留在运行记录中；三栏限制在视口内，各区域独立滚动；
- 首版任务输出在工具完成后出现，后续可在不开放 Shell 的前提下增加受控输出事件；
- 部署标签页仅占位，当前阶段不具备 Docker、Nginx、发布或回滚权限。
