# ADR 0008：统一安全执行网关与结构化审计

## 状态

已采用，2026-09-05 实现。

## 决策

- ChatService 不再自行完成风险判断、审批和工具执行，而是把所有工具调用交给 `SafeExecutionGateway`。
- 网关按固定顺序处理：工具授权由运行时主链确认，随后进行输入 Schema 校验、风险判断、参数绑定审批、取消检查、限时执行和审计。
- `ToolInputValidator` 支持当前工具需要的 JSON Schema 子集，包括 object、properties、required、additionalProperties 和常用字段类型；工具执行器继续负责文件名、长度、时区等业务语义校验。
- Flyway V5 新增 `audit_event`，并给审批补充 conversationId、agentVersionId、能力类别、风险等级和目标环境。
- 审计记录工具、能力、风险、状态、参数 SHA-256、摘要和时间，不保存原始参数或完整工具输出；需要查看原参数时仍以受限时效的 ApprovalRequest 和 RunStep 为准。
- 运行详情页同时展示 RunStep 和安全审计，便于区分“智能体做了什么”与“安全网关为什么允许或阻止”。

## 取舍与边界

当前校验器不是完整 JSON Schema Draft 实现，只覆盖已声明的稳定子集；新增复杂 MCP Schema 前应引入经过维护的标准校验库或扩展测试。审计接口服务于无登录的本地单机工具，尚无审计主体、审批人身份、租户隔离、签名防篡改和保留策略，不能描述为企业合规审计。
