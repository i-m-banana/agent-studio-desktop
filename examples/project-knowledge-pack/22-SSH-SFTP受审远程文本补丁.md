# Agent Studio Desktop：SSH/SFTP 受审远程文本补丁

## 当前状态

2026-09-15，在已验收的 SSH/SFTP 远程只读工作区之后，项目新增 `apply_remote_workspace_text_patch`。首次真实 Ubuntu/OpenSSH 测试因 SFTP v3 不接受标准 rename 的 `Atomic/Overwrite` 选项而失败，文件安全保持原样；根因是测试 Server 默认协商高版本，未贴近主要服务对象。修复优先使用 OpenSSH `posix-rename@openssh.com`，并把自动化强制为 v3，同时覆盖扩展缺失失败关闭。全量后端 54 个测试和前端生产构建通过，随后真实服务器与模型的批准修改、批准恢复、拒绝和陈旧摘要分支均通过。当前可以表述为“固定指纹、单远程根、密码认证的受审远程单文件文本补丁已验收”。

## 最小闭环

Agent 先用远程读取工具取得文件内容与完整 SHA-256，再提出 `SSH/WRITE/HIGH` 补丁调用。参数只包含授权根内既有文件的相对路径、读取摘要和最多 10 组唯一精确替换。SafeExecutionGateway 在执行前完成 Schema 与风险校验，并要求用户批准参数绑定的一次性 ApprovalRequest；拒绝、过期和运行取消均不得写入。

批准后，工具重新读取文件并校验摘要与严格 UTF-8，每段旧文本必须恰好出现一次。新内容先进入同目录独占临时文件，复制原权限，提交前再次核对摘要，再要求 SFTP Server 原子覆盖目标。结果返回远端目标、路径、前后摘要、替换数与字节数。它不是 MCP Server，仍复用 AgentVersion、ToolRegistry、RunStep 和 AuditEvent 基线。

## 安全边界

路径逐级 `lstat` 并拒绝符号链接、越界、敏感目录和真实 `.env`；文件与结果不超过 1 MiB，补丁参数合计不超过 12000 字符。服务器不支持原子重命名时失败关闭，不退化为直接写原文件。SFTP 没有跨客户端事务锁，最后一次摘要检查与原子替换之间仍有极小并发窗口。

真实运行证据：`c02f0f09-ad25-4a29-9717-5933f1830927` 完成正向修改，`1349ba57-8f72-467d-a8c4-51f223387094` 完成恢复，`3381d12b-4465-4350-b3dd-03750fb909de` 形成审批拒绝与 TOOL_EXECUTION_SKIPPED，`e139b076-8946-4322-bb97-ea9a18799eae` 两次拒绝陈旧摘要并保留人工新内容。修复前 `33853a3f-46cd-4100-a048-769bd3327fb7` 的真实失败记录保留为兼容问题证据。

本阶段本身没有新建、删除、任意上传、统一 diff、跨文件事务、远程 Shell、Git、构建测试、日志命令、Docker/Nginx 或部署。后续独立阶段已经增加仅自动化通过的固定受控 SSH Exec，但不改变本阶段的补丁边界。人工清单见 `docs/SSH_SFTP_PATCH_ACCEPTANCE.md`。
