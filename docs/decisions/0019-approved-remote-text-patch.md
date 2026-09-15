# ADR 0019：固定指纹 SSH/SFTP 的受审远程文本补丁

## 背景

远程只读工作区已经能定位和读取云服务器代码，但还不能形成“本机对话—远程修改—可追溯确认”的最小闭环。直接开放远程 Shell、任意上传或覆盖会显著扩大副作用面，因此本阶段只复用已经在本地验证过的摘要绑定精确替换模型。

## 决策

新增 `apply_remote_workspace_text_patch`，来源为 `SSH`、能力为 `WRITE`、风险为 `HIGH`。工具通过原有 AgentVersion 固化描述，由 ToolRegistry 注册并统一进入 SafeExecutionGateway；每次调用必须形成与完整参数绑定、限时且一次性的 ApprovalRequest，执行事实继续写入 RunStep 和 AuditEvent。它不是 MCP Server，也不增加旁路文件接口。

输入只允许远程根内既有普通文件的相对路径、`read_remote_workspace_text_file` 返回的 64 位 SHA-256，以及 1–10 组精确 `{oldText,newText}`。每段旧文本必须恰好出现一次，补丁文本合计不超过 12000 字符，输入和结果均不超过 1 MiB 且必须是严格 UTF-8 文本。摘要过期、歧义匹配、符号链接、越界和受保护路径全部拒绝。

执行时在目标同目录独占创建随机临时文件，写入新内容并复制原权限；提交前重新读取目标并比较摘要，然后要求 SFTP Server 以原子覆盖方式重命名。OpenSSH 固定使用 SFTP v3，因此优先检测并调用其 `posix-rename@openssh.com` 扩展；其他 v5+ Server 可使用标准 `Atomic/Overwrite` rename。两种安全方式都不可用时失败关闭，不回退为直接覆盖；失败时尽力清理临时文件。

## 结果与边界

本阶段只提供单文件、既有文件、精确文本替换。SFTP 缺少通用跨客户端事务锁，因此最后一次摘要检查与原子重命名之间仍存在极小的并发窗口；后续 Git 工作树事务可进一步收紧。当前没有文件新建/删除、通用 diff、跨文件事务、远程 Shell、Git、构建测试、Docker/Nginx 或部署。阶段结论必须分别记录自动化与真实服务器/模型验收证据，不能仅凭自动化升级口径。
