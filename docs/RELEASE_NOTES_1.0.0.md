# Agent Studio Desktop 1.0.0

发布日期：2026-09-13。

## 发布结论

用户已在日常运行环境中完成网站主要功能的人工测试，并确认首个单机正式版通过验收。发布前质量门禁继续执行后端自动化测试、前端可复现生产构建、版本一致性与敏感文件检查；通过后以 Git 注释标签 `v1.0.0` 固化可回退代码锚点。

## 正式版能力边界

本版本交付 Agent 草稿与不可变版本、会话版本绑定、RAG、DeepSeek/OpenAI 兼容模型调用、SSE、显式 ReAct、安全执行网关、参数绑定审批、运行取消与超时、RunStep/AuditEvent、MCP HTTP/stdio、Resources/Prompts、DPAPI 凭据、系统诊断及本机发布运维脚本。

正式标签前的发布检查发现 Windows 后台启动包装进程可能先于实际 Java/Node 服务退出，导致旧停止脚本只记录包装 PID 时无法关闭已经被重新托管的子进程。启动脚本随后同时保存包装 PID 和实际监听 8080/5173 的进程 PID，停止脚本兼容清理两者，避免后台服务残留并占用端口或前端构建文件。

`1.0.0` 不声称已经支持通用 Coding、SSH/SFTP、Docker/Nginx 远程部署、运维托管、登录/RBAC、MCP OAuth、OS 级沙箱或崩溃断点续跑。

## 后续主线

后续优先把简历中的工程目标转化为真实产品能力：先建立受限本地代码工作区和读写分级工具，再接入 SSH/SFTP，最后完成个人网站的 Docker/Nginx 部署、健康检查、失败收口与可追溯验收。所有新工具必须复用既有 AgentVersion、ToolRegistry、SafeExecutionGateway、ApprovalRequest、RunStep 与 AuditEvent，不得绕开正式版安全基线。
