import http from 'node:http'

const port = Number(process.env.MCP_FIXTURE_PORT ?? 3001)
const tools = [
  {
    name: 'project_milestone',
    title: '查询项目里程碑',
    description: '根据阶段编号返回 Agent Studio Desktop 的演示里程碑信息。',
    inputSchema: {
      type: 'object',
      properties: { stage: { type: 'integer', description: '阶段编号，1 到 6' } },
      required: ['stage'],
      additionalProperties: false
    }
  },
  {
    name: 'summarize_checklist',
    title: '整理检查清单',
    description: '把多条检查项整理为带序号的文本，用于验证数组参数和 MCP 结果回传。',
    inputSchema: {
      type: 'object',
      properties: {
        title: { type: 'string' },
        items: { type: 'array', items: { type: 'string' } }
      },
      required: ['title', 'items'],
      additionalProperties: false
    }
  }
]

function response(id, result) { return { jsonrpc: '2.0', id, result } }
function error(id, code, message) { return { jsonrpc: '2.0', id, error: { code, message } } }

const server = http.createServer(async (request, reply) => {
  if (request.method === 'DELETE') { reply.writeHead(204); reply.end(); return }
  if (request.method !== 'POST' || request.url !== '/mcp') { reply.writeHead(404); reply.end(); return }
  let raw = ''
  for await (const chunk of request) raw += chunk
  let message
  try { message = JSON.parse(raw) } catch { send(reply, error(null, -32700, 'Parse error')); return }
  if (message.method === 'notifications/initialized') { reply.writeHead(202); reply.end(); return }
  if (message.method === 'initialize') {
    send(reply, response(message.id, {
      protocolVersion: '2025-06-18', capabilities: { tools: { listChanged: false } },
      serverInfo: { name: 'agent-studio-fixture', version: '1.0.0' }
    }))
    return
  }
  if (request.headers['mcp-protocol-version'] !== '2025-06-18') {
    send(reply, error(message.id, -32600, 'Missing or unsupported MCP-Protocol-Version')); return
  }
  if (message.method === 'tools/list') { send(reply, response(message.id, { tools })); return }
  if (message.method !== 'tools/call') { send(reply, error(message.id, -32601, 'Method not found')); return }
  const { name, arguments: args = {} } = message.params ?? {}
  if (name === 'project_milestone') {
    const stage = Number(args.stage)
    const milestones = {
      1: '本地底座与流式模型调用', 2: 'Agent 草稿和不可变版本', 3: 'RAG 文档与向量检索',
      4: '显式 ReAct 与工具运行时', 5: '审批、取消和结构化审计', 6: 'MCP Streamable HTTP 适配'
    }
    const text = milestones[stage]
    send(reply, response(message.id, text
      ? { content: [{ type: 'text', text: `阶段 ${stage}：${text}` }], structuredContent: { stage, milestone: text }, isError: false }
      : { content: [{ type: 'text', text: '阶段编号必须在 1 到 6 之间' }], isError: true }))
    return
  }
  if (name === 'summarize_checklist') {
    const items = Array.isArray(args.items) ? args.items : []
    const text = `${args.title ?? '检查清单'}\n${items.map((item, index) => `${index + 1}. ${item}`).join('\n')}`
    send(reply, response(message.id, { content: [{ type: 'text', text }], isError: false })); return
  }
  send(reply, error(message.id, -32602, `Unknown tool: ${name}`))
})

function send(reply, body) {
  const text = JSON.stringify(body)
  reply.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) })
  reply.end(text)
}

server.listen(port, '127.0.0.1', () => {
  console.log(`Agent Studio MCP fixture listening on http://127.0.0.1:${port}/mcp`)
})
