# ADR 0030：固定任务显式请求与执行证据

## 原因

2026-09-29，a612cb2b-0e62-44c3-8cd3-550c5b15a7a8和4dd68bf1-9df1-4d66-a206-4713fc372a29只有MODEL_CALL，没有调用/审批/结果。模型分别声称agentstudio已有基线及deploy认证失败，均无执行证据。实际f401d45d-d2ab-4cab-93c1-73f0eb6f6fa4执行DATABASE_BASELINE_STATUS，old_things历史表不存在。不能据无证据回复修改凭据或登记数据库。

## 决策

固定任务按钮在现有 /api/chat/stream 消息中增加可选 requestedTool={name,arguments}。限于已实现的七个工作区/部署业务工具，必须绑定当前AgentVersion，参数为有界对象，并再次经过ToolInputValidator、SafeExecutionGateway、参数/目标绑定的一次性ApprovalRequest。平台产生USER_TOOL_REQUEST和唯一调用ID，后续沿用同一TOOL_CALL/APPROVAL_REQUEST/APPROVAL_RESULT/TOOL_RESULT与AuditEvent。不新增直连执行接口，不绕过审批，不允许命令/SQL/路径扩权，不包装MCP。拒绝不执行，取消与总时限沿用已有机制。

按钮选择已经确定时，不再让模型决定是否调用、改写参数或重写结果。直接呈现原始有界工具结果，successful=false/非零退出不变成成功；异常呈现工具失败，拒绝呈现未执行。保留自然语言助手及普通ReAct/RAG/MCP能力。自然语言明确含绑定工具名和使用/调用/运行/执行而模型未产生任何执行证据时，用确定性“任务未执行”替代最终回复，并记录EXECUTION_EVIDENCE_CHECK。该启发式不是通用语义真实性证明；有执行证据的自由聊天仍是模型解释，不能替代原始工具结果。

历史无证据MODEL_CALL不删除、不修造；它们不用于六项身份填充。结构摘要仍仅由真实完整工具结果填入。旧接口三字段请求兼容。

## 验收

自动化需证明固定请求不调用模型、审批前不执行、批准仅执行一次、拒绝零执行、危险参数在网关拒绝、未绑定拒绝、原始失败不改写以及自然语言无证据回复被拦截。真实服务器结构核查与基线登记待用户分别审批；不能由模拟远程适配器测试宣称已人工验收。
