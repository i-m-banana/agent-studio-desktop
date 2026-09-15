# ADR 0018：固定指纹的 SSH/SFTP 远程只读工作区

## 决策

SSH 第一阶段只提供三个 `READ/LOW` 工具：`list_remote_workspace_directory`、`search_remote_workspace_files` 和 `read_remote_workspace_text_file`。工具使用 Apache MINA SSHD 2.19.0 建立短生命周期 SSH 会话，并通过 SFTP 完成文件操作；不执行远程 Shell，也不把 SSH 伪装成 MCP Server。

连接目标优先由页面写入 MySQL 的单行配置给出，启动环境变量仅作为未保存页面配置时的部署回退；一个实例只授权一个非 `/` 的绝对 POSIX 远程根目录。密码只从环境变量或既有 Windows DPAPI 安全凭据读取，不进入业务数据库、工具参数、模型上下文和返回值。页面可以在不发送凭据、不进行认证的情况下探测服务器公钥，但用户必须在云控制台或服务器管理员处独立核对后才保存。客户端禁用用户目录 SSH config、默认密钥和默认密码来源，只使用显式目标与指定凭据；正式连接必须对 `SHA256:...` 指纹做恒定时间比对，不接受静默更新。

远程路径只接受相对路径，规范化后必须留在授权根目录；访问前对根目录到目标的每级路径执行 `lstat`，拒绝符号链接。`.git`、`.ssh`、`.env`、`data/secrets` 和常见私钥后缀继续受保护。搜索仅按路径匹配，最多扫描 3000 项、深度 16，跳过依赖和构建目录；文本读取限制为 1 MiB、有效 UTF-8、500 行和约 16000 输出字符。

三个工具仍作为 `AgentTool` 进入原 `ToolRegistry`，由 AgentVersion 固化并经 `SafeExecutionGateway`、RunStep 与 AuditEvent 执行。`ToolRegistry.targetEnvironment` 现在允许内置适配器报告实际目标，SSH 工具显示 `SSH:user@host:port/root`，既有本地工具仍默认 `LOCAL`。

## 边界

本阶段仅支持密码认证和单个页面配置目标；没有密钥认证、多目标、连接池、远程 Shell、内容搜索、写入、上传、删除、重命名、补丁、Git、构建测试、Docker/Nginx 操作或部署。自动化通过后只能写“远程只读 SSH/SFTP 已自动验证”；完成真实服务器与真实模型清单后才能写“已人工验收”。
