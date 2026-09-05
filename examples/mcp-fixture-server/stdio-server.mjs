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
      protocolVersion: '2025-06-18', capabilities: { tools: { listChanged: false }, resources: { listChanged: false }, prompts: { listChanged: false } },
      serverInfo: { name: 'agent-studio-stdio-fixture', version: '1.0.0' }
    })
    return
  }
  if (message.method === 'tools/list') { result(message.id, { tools }); return }
  if (message.method === 'resources/list') {
    result(message.id, { resources: [{ uri: 'project://mcp/acceptance', name: 'mcp-acceptance', title: 'MCP 集中验收说明', description: '可导入知识库的 MCP 验收材料', mimeType: 'text/markdown' }] }); return
  }
  if (message.method === 'resources/read' && message.params?.uri === 'project://mcp/acceptance') {
    result(message.id, { contents: [{ uri: message.params.uri, mimeType: 'text/markdown', text: '# MCP 集中验收\n\n该内容来自 stdio MCP Resource，可预览并导入知识库。' }] }); return
  }
  if (message.method === 'prompts/list') {
    result(message.id, { prompts: [{ name: 'mcp_acceptance', title: '生成 MCP 验收问题', description: '根据模块名生成一条验收指令', arguments: [{ name: 'module', description: '待验收模块', required: true }] }] }); return
  }
  if (message.method === 'prompts/get' && message.params?.name === 'mcp_acceptance') {
    const moduleName = String(message.params.arguments?.module ?? '')
    if (!moduleName) { error(message.id, -32602, 'module is required'); return }
    result(message.id, { description: 'MCP 验收提示词', messages: [{ role: 'user', content: { type: 'text', text: `请完整验收 ${moduleName} 模块，并列出工具、资源和提示词结果。` } }] }); return
  }
  if (message.method === 'tools/call' && message.params?.name === 'local_project_status') {
    const moduleName = String(message.params.arguments?.module ?? '').trim()
    result(message.id, { content: [{ type: 'text', text: `${moduleName} 模块：本机 stdio MCP 调用正常` }], isError: false })
    return
  }
  error(message.id, -32601, 'Method or tool not found')
})

console.error('agent-studio stdio fixture started')
