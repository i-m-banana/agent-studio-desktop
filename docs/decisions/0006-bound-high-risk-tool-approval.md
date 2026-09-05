# ADR 0006：参数绑定的高风险工具审批

## 状态

已采用，最小闭环于 2026-09-05 实现。

## 决策

- `ToolDescriptor.riskLevel=HIGH` 的调用不得直接执行；运行进入 `WAITING_APPROVAL` 并发送 `approval_required` SSE。
- `ApprovalRequest` 持久化 runId、toolCallId、工具名、原始参数、参数 SHA-256、状态、创建/过期/决定时间和理由。
- 用户批准或拒绝前可以看到原始参数。批准后执行网关用同一参数摘要原子地把状态从 APPROVED 改为 CONSUMED；重复审批或重复消费均失败。
- 默认 90 秒过期。拒绝或过期作为 Observation 返回模型，但工具不执行。
- 首个高风险样例 `write_workspace_note` 只能在 `data/tool-workspace` 新建 `.md/.txt`，限制文件名与内容长度，拒绝路径穿越和覆盖。

## 边界

当前等待器位于单进程内存，数据库保存审批事实，但应用重启后不会恢复正在等待的 SSE 运行。尚未实现身份认证、审批人身份、运行取消、崩溃恢复、通用策略表达式、SSH 或命令执行；因此不能把该闭环描述为完整企业审计系统。
