# ADR 0002：发布快照与 SSE 事件协议

- 状态：已接受
- 日期：2026-09-02

## Agent 版本

`AgentDefinition` 保存可编辑草稿。每次发布创建新的 `AgentVersion`，快照包含模型配置标识与名称、供应商、API 地址、模型名、密钥环境变量名、温度、系统提示词和可选知识库标识。

修改模型配置或 Agent 草稿不会修改已经发布的版本。会话首次创建后固定绑定一个 `agentVersionId`，后续消息不得切换版本。

## 密钥

数据库和 API 不接受模型密钥明文，只保存环境变量名。模型调用发生时从后端进程环境读取；缺少变量时通过 SSE `error` 事件返回明确错误。

## SSE 协议

`POST /api/chat/stream` 接收：

```json
{
  "agentVersionId": "uuid",
  "conversationId": "可选 uuid",
  "message": "用户消息"
}
```

事件顺序：

1. `run`：包含 `conversationId`、`agentVersionId` 和版本号；
2. `sources`：绑定知识库且命中片段时出现一次，包含来源文件、chunk 编号、内容和相似度；
3. `delta`：包含一次模型文本增量，可出现多次；
4. `done`：正常结束；或 `error`：包含安全化后的失败原因。

当前协议只覆盖普通流式对话。后续 ReAct 阶段会新增工具请求、等待确认、观察结果等事件，但不会绕开统一执行网关。
