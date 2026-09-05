import readline from 'node:readline'

const tools = [{
  name: 'local_project_status',
  title: '查询本机项目状态',
  description: '通过 stdio MCP 返回指定模块的演示状态，用于验证本地子进程传输。',
  inputSchema: {
    type: 'object',
    properties: { module: { type: 'string', description: '模块名，例如 MCP 或 RAG' } },
    required: ['module'],
    additionalProperties: false
  }
}]

function send(message) { process.stdout.write(`${JSON.stringify(message)}\n`) }
function result(id, value) { send({ jsonrpc: '2.0', id, result: value }) }
function error(id, code, message) { send({ jsonrpc: '2.0', id, error: { code, message } }) }

readline.createInterface({ input: process.stdin, crlfDelay: Infinity }).on('line', (line) => {
  let message
  try { message = JSON.parse(line) } catch { error(null, -32700, 'Parse error'); return }
  if (message.method === 'notifications/initialized' || message.method === 'notifications/cancelled') return
  if (message.method === 'initialize') {
    result(message.id, {
      protocolVersion: '2025-06-18', capabilities: { tools: { listChanged: false } },
      serverInfo: { name: 'agent-studio-stdio-fixture', version: '1.0.0' }
    })
    return
  }
  if (message.method === 'tools/list') { result(message.id, { tools }); return }
  if (message.method === 'tools/call' && message.params?.name === 'local_project_status') {
    const moduleName = String(message.params.arguments?.module ?? '').trim()
    result(message.id, { content: [{ type: 'text', text: `${moduleName} 模块：本机 stdio MCP 调用正常` }], isError: false })
    return
  }
  error(message.id, -32601, 'Method or tool not found')
})

console.error('agent-studio stdio fixture started')
