# Agent Studio Desktop：Coding 受审文本补丁子阶段

## 当前状态

2026-09-15，`feat/coding-workspace` 在已验收的只读 Coding 工作区之后增加轻量文本补丁能力。代码、自动化与真实模型的批准/拒绝主链已经完成；陈旧摘要由用户确认和自动化覆盖，但缺少独立运行 ID。当前可以表述为“受审文本补丁子阶段已验收”，仍不能写成“通用 Coding 已完成”。

## 最小闭环

读取工具现在返回文件原始字节的 SHA-256。Agent 依据读取内容提出 `apply_workspace_text_patch` 调用，参数包含相对路径、读取时摘要和最多 10 组精确文本替换。该工具是内置 `WRITE/HIGH`，不是 MCP Server；它仍由 AgentVersion 固化工具描述，通过 SafeExecutionGateway 触发参数绑定的一次性 ApprovalRequest，并在 RunStep 与 AuditEvent 留证。

只有用户批准后才进入写工具。工具重新读取既有普通文件并核对摘要，每段旧文本必须在顺序变更后的当前内容中恰好出现一次。它在同目录生成临时文件，写入后再次检查目标摘要，再做原子替换。拒绝、审批过期、摘要过期、匹配缺失、匹配多处、路径越界、受保护路径、非 UTF-8、NUL 二进制、超限文件或不支持原子替换时都不会完成覆盖。

## 为什么不用重型搜索或通用 diff

当前目标是支持小范围、可解释的代码改动，而不是实现完整 IDE 补丁引擎。精确替换比模糊 diff 更容易把审批参数、读取版本和最终副作用绑定起来，也能直接拒绝歧义。限制为单个既有文件、最多 10 处替换和 12000 字符补丁，使 ApprovalRequest 的参数规模与本地工具时限保持可控。

## 自动化证据与边界

单元测试覆盖工具注册风险、读取摘要、成功替换、陈旧摘要、重复文本和路径越界，并确认失败时原文件不变。会话集成测试真实走过 AgentVersion、ApprovalRequest 批准、SafeExecutionGateway、文件变更、RunStep 和 AuditEvent。加入这些测试后全量后端为 41 个测试通过、0 失败、0 错误、0 跳过；人工清单见 `docs/CODING_PATCH_ACCEPTANCE.md`。

真实批准运行 `3cc5b2f0-e54f-424a-804c-6cc801fa112f` 完成正向替换，`10c216d9-3c49-433f-9cf9-f5b3cbdaace9` 完成反向恢复，拒绝运行 `814047b0-54f8-43af-ac9d-9b53622079fc` 形成 TOOL_EXECUTION_SKIPPED。本阶段仍未实现补丁预览专页、撤销、统一 diff、模糊匹配、新建/删除/重命名、跨文件事务、Git、命令、构建测试、SSH/SFTP 或部署。下一步评估白名单本地验证命令。
