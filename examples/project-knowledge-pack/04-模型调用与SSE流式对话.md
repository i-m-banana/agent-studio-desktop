# Agent Studio Desktop：模型调用与 SSE 流式对话

## OpenAI 兼容适配

项目没有直接绑定某个厂商 SDK，而是使用 Java `HttpClient` 调用 OpenAI Chat Completions 兼容接口。网关从 AgentVersion 读取 baseUrl、modelName、temperature 和 apiKeyEnv。如果 baseUrl 没有以 `/chat/completions` 结束，网关自动补齐路径。

请求体包含 `model`、`temperature`、`stream: true` 和 messages。密钥通过 `System.getenv(apiKeyEnv)` 在运行时读取，并以 Bearer Token 发送。如果环境变量不存在，服务通过 SSE 返回明确错误，例如“环境变量 DEEPSEEK_API_KEY 未设置”。数据库、接口响应和 Git 都不包含真实密钥。

## SSE 事件协议

对话接口是 `POST /api/chat/stream`。请求包含 `agentVersionId`、可选 `conversationId` 和 message。

服务首先返回 `run`，其中有 conversationId、agentVersionId 和 versionNumber。若知识库检索命中，则返回一次 `sources`，包含 documentId、fileName、chunkIndex、content 和 score。模型每产生一段文本就返回 `delta`。正常结束返回 `done`，异常返回 `error`。

正常 RAG 对话的典型顺序是：`run → sources → delta → delta → ... → done`。未绑定知识库或没有命中时没有 sources。模型密钥缺失时可能是 `run → sources → error`，这说明检索已经完成，只是外部模型调用失败。

用户主动取消或达到总时限时，服务发送 `terminated`，其中包含 runId、CANCELLED/TIMED_OUT 和原因，然后结束 SSE。已经发到浏览器的部分 delta 可以被看到，但不会保存为一条完整 assistant 消息。SSE 超时比运行总时限多留 10 秒，用于发送终止状态和完成清理。

## 会话上下文

收到用户消息后，后端先保存消息，再在异步任务中执行流式响应。模型消息的组装顺序是：AgentVersion 的系统提示词、可选知识库上下文、当前会话历史消息。知识库上下文要求模型优先依据片段回答，证据不足时明确说明，并标注来源文件和 chunk。

模型完整回答会在流结束后以 assistant 消息写入 MySQL。若模型中途失败，当前实现不会保存不完整 assistant 文本。SSE emitter 超时为 120 秒，HTTP 模型请求也设置 120 秒超时。

绑定工具时使用非流式 Chat Completions 获取一轮完整的 assistant 消息和 tool_calls。平台把已绑定工具请求交给 SafeExecutionGateway，完成 Schema 校验、风险审批、取消检查、限时执行和审计，再把 Observation 加入消息后调用模型，直到得到最终文本或达到最大轮数。每一轮产生 step 事件。遇到 HIGH 工具时，服务发送 `approval_required` 并把运行标为 WAITING_APPROVAL；事件除原始参数与 SHA-256 外，还展示能力、风险和目标环境。前端调用独立的 approve 或 reject 接口，服务再发送 APPROVAL_RESULT。批准只对界面展示的原始参数有效，拒绝和超时作为 Observation 返回模型。工具版最终文本当前以一个 delta 事件返回；未绑定工具的路径仍然逐 token 转发多个 delta。

## 为什么选 SSE

当前输出方向仍是服务端到浏览器，因此 SSE 足够。审批决定本身是一次普通 HTTP POST，不需要为了偶发的用户动作把整条链路改成 WebSocket。SSE 承载 run、sources、step、approval_required、delta、done 和 error；只有未来需要高频双向控制时才有必要考虑 WebSocket。
