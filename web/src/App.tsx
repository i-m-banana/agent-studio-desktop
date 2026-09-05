import { FormEvent, useEffect, useMemo, useState } from 'react'

type BackendState = 'checking' | 'online' | 'offline'
type View = 'models' | 'knowledge' | 'mcp' | 'agents' | 'chat' | 'runs'
type ModelProfile = { id: string; name: string; provider: string; baseUrl: string; modelName: string; apiKeyEnv: string; temperature: number }
type KnowledgeBase = { id: string; name: string; description: string }
type KnowledgeDocument = { id: string; fileName: string; fileSize: number; status: string; chunkCount: number; errorMessage?: string }
type ReindexResult = { knowledgeBaseId: string; embedding: string; documentCount: number; chunkCount: number }
type RagSource = { documentId: string; fileName: string; chunkIndex: number; content: string; score: number }
type ToolDefinition = { name: string; displayName: string; description: string; source: string; capability: string; riskLevel: string; timeoutSeconds: number }
type AgentDefinition = { id: string; name: string; description: string; draftModelProfileId: string; draftKnowledgeBaseId?: string; draftSystemPrompt: string; draftToolNames: string[]; latestVersionNumber: number; status: 'DRAFT' | 'PUBLISHED' }
type AgentVersion = { id: string; agentDefinitionId: string; versionNumber: number; modelProfileName: string; modelName: string; systemPrompt: string; toolNames: string[] }
type ChatMessage = { role: 'user' | 'assistant'; content: string }
type RunStep = { id: string; stepNumber: number; stepType: string; status: string; toolName?: string; inputJson?: string; outputText?: string; durationMs?: number }
type ApprovalRequest = { id: string; toolName: string; capability: string; riskLevel: string; targetEnvironment: string; argumentsJson: string; argumentsSha256: string; status: string; expiresAt: string }
type RunSummary = { id: string; conversationId: string; agentVersionId: string; status: string; startedAt: string; completedAt?: string; errorMessage?: string; stepCount: number }
type AgentRun = Omit<RunSummary, 'stepCount'> & { steps: RunStep[] }
type AuditEvent = { id: string; eventType: string; toolName?: string; capability?: string; riskLevel?: string; status: string; argumentsSha256?: string; details?: string; createdAt: string }
type McpTool = { publicName: string; remoteName: string; displayName: string; description: string; capability: string; riskLevel: string; timeoutSeconds: number; active: boolean; enabled: boolean; schemaSha256: string }
type McpServer = { id: string; name: string; transport: 'STREAMABLE_HTTP' | 'STDIO'; endpointUrl?: string; apiKeyEnv?: string; command?: string; arguments: string[]; workingDirectory?: string; environment: Record<string, string>; enabled: boolean; status: string; protocolVersion?: string; remoteServerName?: string; remoteServerVersion?: string; lastError?: string; lastSyncedAt?: string; tools: McpTool[] }
type McpResource = { publicId: string; uri: string; name: string; displayName: string; description: string; mimeType?: string; size?: number; active: boolean }
type McpPromptArgument = { name: string; description: string; required: boolean }
type McpPrompt = { publicName: string; remoteName: string; displayName: string; description: string; arguments: McpPromptArgument[]; active: boolean }
type McpPromptResult = { description: string; messages: { role: string; content: unknown }[] }
type McpSyncEvent = { id: string; status: string; protocolVersion?: string; toolCount: number; resourceCount: number; promptCount: number; added: number; removed: number; unchanged: number; errorMessage?: string; createdAt: string }
type McpConfiguration = { name: string; transport: 'STREAMABLE_HTTP' | 'STDIO'; endpointUrl?: string; apiKeyEnv?: string; command?: string; arguments: string[]; workingDirectory?: string; environment: Record<string, string> }

const emptyModel = { name: '', provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.openai.com/v1', modelName: '', apiKeyEnv: 'OPENAI_API_KEY', temperature: 0.7 }
const emptyAgent = { name: '', description: '', modelProfileId: '', knowledgeBaseId: '', systemPrompt: '', toolNames: [] as string[] }
const emptyMcp = { name: '', transport: 'STREAMABLE_HTTP' as 'STREAMABLE_HTTP' | 'STDIO', endpointUrl: 'http://127.0.0.1:3001/mcp', apiKeyEnv: '', command: 'node', argumentsText: '', workingDirectory: '', environmentText: '' }

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } })
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: `HTTP ${response.status}` }))
    throw new Error(body.message ?? `HTTP ${response.status}`)
  }
  return response.json() as Promise<T>
}

function App() {
  const [backendState, setBackendState] = useState<BackendState>('checking')
  const [view, setView] = useState<View>('models')
  const [models, setModels] = useState<ModelProfile[]>([])
  const [agents, setAgents] = useState<AgentDefinition[]>([])
  const [tools, setTools] = useState<ToolDefinition[]>([])
  const [mcpServers, setMcpServers] = useState<McpServer[]>([])
  const [mcpForm, setMcpForm] = useState(emptyMcp)
  const [editingMcpId, setEditingMcpId] = useState<string>()
  const [mcpAssets, setMcpAssets] = useState<Record<string, { resources: McpResource[]; prompts: McpPrompt[] }>>({})
  const [mcpPreview, setMcpPreview] = useState<{ title: string; content: string }>()
  const [mcpSyncEvents, setMcpSyncEvents] = useState<Record<string, McpSyncEvent[]>>({})
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [selectedKnowledgeBase, setSelectedKnowledgeBase] = useState('')
  const [knowledgeForm, setKnowledgeForm] = useState({ name: '', description: '' })
  const [versions, setVersions] = useState<AgentVersion[]>([])
  const [modelForm, setModelForm] = useState(emptyModel)
  const [agentForm, setAgentForm] = useState(emptyAgent)
  const [editingAgentId, setEditingAgentId] = useState<string>()
  const [selectedVersion, setSelectedVersion] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [chatInput, setChatInput] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sources, setSources] = useState<RagSource[]>([])
  const [runSteps, setRunSteps] = useState<RunStep[]>([])
  const [pendingApproval, setPendingApproval] = useState<ApprovalRequest>()
  const [approvalBusy, setApprovalBusy] = useState(false)
  const [currentRunId, setCurrentRunId] = useState<string>()
  const [cancelBusy, setCancelBusy] = useState(false)
  const [runHistory, setRunHistory] = useState<RunSummary[]>([])
  const [selectedRun, setSelectedRun] = useState<AgentRun>()
  const [selectedAuditEvents, setSelectedAuditEvents] = useState<AuditEvent[]>([])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')

  const versionLabels = useMemo(() => {
    const names = new Map(agents.map((agent) => [agent.id, agent.name]))
    return versions.map((version) => ({ ...version, label: `${names.get(version.agentDefinitionId) ?? 'Agent'} · v${version.versionNumber} · ${version.modelName}${version.toolNames.length ? ` · ${version.toolNames.length} 工具` : ''}` }))
  }, [agents, versions])

  async function refresh() {
    try {
      const [nextModels, nextAgents, nextBases, nextTools, nextRuns, nextMcpServers] = await Promise.all([api<ModelProfile[]>('/api/models'), api<AgentDefinition[]>('/api/agents'), api<KnowledgeBase[]>('/api/knowledge-bases'), api<ToolDefinition[]>('/api/tools'), api<RunSummary[]>('/api/runs?limit=50'), api<McpServer[]>('/api/mcp/servers')])
      const groups = await Promise.all(nextAgents.map((agent) => api<AgentVersion[]>(`/api/agents/${agent.id}/versions`)))
      setModels(nextModels); setAgents(nextAgents); setVersions(groups.flat()); setKnowledgeBases(nextBases); setTools(nextTools); setRunHistory(nextRuns); setMcpServers(nextMcpServers); setBackendState('online')
      const assetPairs = await Promise.all(nextMcpServers.map(async (server) => {
        try { return [server.id, { resources: await api<McpResource[]>(`/api/mcp/servers/${server.id}/resources`), prompts: await api<McpPrompt[]>(`/api/mcp/servers/${server.id}/prompts`) }] as const }
        catch { return [server.id, { resources: [], prompts: [] }] as const }
      }))
      setMcpAssets(Object.fromEntries(assetPairs))
      const syncPairs = await Promise.all(nextMcpServers.map(async (server) => {
        try { return [server.id, await api<McpSyncEvent[]>(`/api/mcp/servers/${server.id}/sync-events`)] as const }
        catch { return [server.id, []] as const }
      }))
      setMcpSyncEvents(Object.fromEntries(syncPairs))
      const baseId = selectedKnowledgeBase || nextBases[0]?.id || ''
      setSelectedKnowledgeBase(baseId)
      setDocuments(baseId ? await api<KnowledgeDocument[]>(`/api/knowledge-bases/${baseId}/documents`) : [])
      setAgentForm((current) => ({ ...current, modelProfileId: current.modelProfileId || nextModels[0]?.id || '' }))
    } catch { setBackendState('offline') }
  }

  useEffect(() => { void refresh() }, [])

  async function perform(action: () => Promise<void>) {
    setBusy(true); setNotice('')
    try { await action() } catch (error) { setNotice(error instanceof Error ? error.message : '操作失败') } finally { setBusy(false) }
  }

  async function submitModel(event: FormEvent) {
    event.preventDefault()
    await perform(async () => {
      await api('/api/models', { method: 'POST', body: JSON.stringify(modelForm) })
      setModelForm(emptyModel); await refresh()
      setNotice('模型配置已保存。密钥值不会写入数据库，请在启动后端前设置对应环境变量。')
    })
  }

  async function submitKnowledgeBase(event: FormEvent) {
    event.preventDefault()
    await perform(async () => {
      const created = await api<KnowledgeBase>('/api/knowledge-bases', { method: 'POST', body: JSON.stringify(knowledgeForm) })
      setKnowledgeForm({ name: '', description: '' }); setSelectedKnowledgeBase(created.id)
      await refresh(); setNotice('知识库已创建，可以上传文档。')
    })
  }

  async function submitMcpServer(event: FormEvent) {
    event.preventDefault()
    await perform(async () => {
      const environment = Object.fromEntries(mcpForm.environmentText.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
        const index = line.indexOf('='); if (index < 1) throw new Error(`环境变量映射格式无效：${line}`)
        return [line.slice(0, index).trim(), line.slice(index + 1).trim()]
      }))
      const payload = { name: mcpForm.name, transport: mcpForm.transport,
        endpointUrl: mcpForm.transport === 'STREAMABLE_HTTP' ? mcpForm.endpointUrl : null,
        apiKeyEnv: mcpForm.transport === 'STREAMABLE_HTTP' ? mcpForm.apiKeyEnv || null : null,
        command: mcpForm.transport === 'STDIO' ? mcpForm.command : null,
        arguments: mcpForm.transport === 'STDIO' ? mcpForm.argumentsText.split('\n').map((line) => line.trim()).filter(Boolean) : [],
        workingDirectory: mcpForm.transport === 'STDIO' ? mcpForm.workingDirectory || null : null,
        environment: mcpForm.transport === 'STDIO' ? environment : {} }
      const id = editingMcpId
      const saved = await api<McpServer>(id ? `/api/mcp/servers/${id}` : '/api/mcp/servers', { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) })
      setNotice('MCP Server 已保存，正在连接并发现工具、资源与提示词……')
      try { await api(`/api/mcp/servers/${saved.id}/sync`, { method: 'POST', body: '{}' }) }
      catch (error) { await refresh(); throw error }
      setEditingMcpId(undefined); setMcpForm(emptyMcp); await refresh()
      setNotice('MCP 连接成功：工具已进入 Agent Builder，资源与提示词已进入下方目录。')
    })
  }

  async function syncMcpServer(id: string) {
    await perform(async () => {
      const result = await api<{ toolCount: number; resourceCount: number; promptCount: number; remoteServerName: string; added: number; removed: number; unchanged: number }>(`/api/mcp/servers/${id}/sync`, { method: 'POST', body: '{}' })
      await refresh(); setNotice(`MCP 同步完成：${result.toolCount} 个工具、${result.resourceCount} 个资源、${result.promptCount} 个提示词；工具新增 ${result.added}、下线 ${result.removed}、未变化 ${result.unchanged}。`)
    })
  }

  async function exportMcpConfiguration(server: McpServer) {
    await perform(async () => {
      const configuration = await api<McpConfiguration>(`/api/mcp/servers/${server.id}/configuration`)
      const blob = new Blob([JSON.stringify(configuration, null, 2)], { type: 'application/json' })
      const link = document.createElement('a'); link.href = URL.createObjectURL(blob)
      link.download = `${server.name.replace(/[^\w\u4e00-\u9fff-]+/g, '_')}-mcp.json`; link.click()
      URL.revokeObjectURL(link.href); setNotice('MCP 配置已导出；文件只包含环境变量名，不包含密钥值。')
    })
  }

  async function importMcpConfiguration(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) return
    await perform(async () => {
      const configuration = JSON.parse(await file.text()) as McpConfiguration
      const server = await api<McpServer>('/api/mcp/servers/import', { method: 'POST', body: JSON.stringify(configuration) })
      try { await api(`/api/mcp/servers/${server.id}/sync`, { method: 'POST', body: '{}' }) } catch { /* 保留配置与失败记录供排查 */ }
      await refresh(); setNotice('MCP 配置已导入，并已尝试连接同步。')
    })
    event.target.value = ''
  }

  function editMcpServer(server: McpServer) {
    setEditingMcpId(server.id)
    setMcpForm({ name: server.name, transport: server.transport, endpointUrl: server.endpointUrl ?? '', apiKeyEnv: server.apiKeyEnv ?? '', command: server.command ?? 'node', argumentsText: (server.arguments ?? []).join('\n'), workingDirectory: server.workingDirectory ?? '', environmentText: Object.entries(server.environment ?? {}).map(([key, value]) => `${key}=${value}`).join('\n') })
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  async function toggleMcpServer(server: McpServer) {
    await perform(async () => { await api(`/api/mcp/servers/${server.id}/enabled`, { method: 'PUT', body: JSON.stringify({ enabled: !server.enabled }) }); await refresh(); setNotice(server.enabled ? 'MCP Server 已停用，历史记录仍然保留。' : 'MCP Server 已启用。') })
  }

  async function deleteMcpServer(server: McpServer) {
    if (!window.confirm(`确认删除“${server.name}”？已被 Agent 引用时平台会阻止删除。`)) return
    await perform(async () => { const response = await fetch(`/api/mcp/servers/${server.id}`, { method: 'DELETE' }); if (!response.ok) { const body = await response.json().catch(() => ({ message: '删除失败' })); throw new Error(body.message) } if (editingMcpId === server.id) { setEditingMcpId(undefined); setMcpForm(emptyMcp) } await refresh(); setNotice('未被引用的 MCP Server 已删除。') })
  }

  async function updateMcpTool(tool: McpTool, changes: Partial<Pick<McpTool, 'enabled' | 'riskLevel' | 'timeoutSeconds'>>) {
    const next = { enabled: changes.enabled ?? tool.enabled, capability: tool.capability, riskLevel: changes.riskLevel ?? tool.riskLevel, timeoutSeconds: changes.timeoutSeconds ?? tool.timeoutSeconds }
    if (tool.riskLevel === 'HIGH' && next.riskLevel === 'LOW' && !window.confirm('降为低风险后将不再要求逐次审批，确认信任这个工具？')) return
    await perform(async () => { await api(`/api/mcp/servers/tools/${tool.publicName}/policy`, { method: 'PUT', body: JSON.stringify(next) }); await refresh(); setNotice('MCP 工具策略已更新；已发布 Agent 版本中的工具名称不变。') })
  }

  async function loadMcpAssets(serverId: string) {
    await perform(async () => {
      const [resources, prompts] = await Promise.all([api<McpResource[]>(`/api/mcp/servers/${serverId}/resources`), api<McpPrompt[]>(`/api/mcp/servers/${serverId}/prompts`)])
      setMcpAssets((current) => ({ ...current, [serverId]: { resources: resources.filter((item) => item.active), prompts: prompts.filter((item) => item.active) } }))
      setNotice(`已加载 ${resources.filter((item) => item.active).length} 个资源和 ${prompts.filter((item) => item.active).length} 个提示词。`)
    })
  }

  async function previewMcpResource(serverId: string, resource: McpResource) {
    await perform(async () => {
      const contents = await api<{ uri: string; mimeType?: string; text?: string; blob?: string }[]>(`/api/mcp/servers/${serverId}/resources/${resource.publicId}`)
      const content = contents.map((item) => item.text ?? (item.blob ? `[二进制内容：约 ${Math.round(item.blob.length * .75)} 字节]` : '[空内容]')).join('\n\n')
      setMcpPreview({ title: resource.displayName, content })
    })
  }

  async function importMcpResource(serverId: string, resource: McpResource) {
    if (!selectedKnowledgeBase) { setNotice('请先在知识库页面创建并选择一个知识库。'); return }
    await perform(async () => {
      await api(`/api/mcp/servers/${serverId}/resources/${resource.publicId}/import`, { method: 'POST', body: JSON.stringify({ knowledgeBaseId: selectedKnowledgeBase, fileName: null }) })
      await chooseKnowledgeBase(selectedKnowledgeBase); setNotice(`“${resource.displayName}”已读取、解析并写入当前知识库。`)
    })
  }

  async function getMcpPrompt(serverId: string, prompt: McpPrompt) {
    const argumentsMap: Record<string, string> = {}
    for (const argument of prompt.arguments) {
      const value = window.prompt(`${argument.name}${argument.required ? '（必填）' : '（可选）'}\n${argument.description}`, '')
      if (value === null) return
      if (argument.required && !value.trim()) { setNotice(`缺少必填参数：${argument.name}`); return }
      if (value.trim()) argumentsMap[argument.name] = value.trim()
    }
    await perform(async () => {
      const result = await api<McpPromptResult>(`/api/mcp/servers/${serverId}/prompts/${prompt.publicName}`, { method: 'POST', body: JSON.stringify({ arguments: argumentsMap }) })
      const content = result.messages.map((message) => `[${message.role}]\n${typeof message.content === 'object' && message.content && 'text' in message.content ? String((message.content as { text: unknown }).text) : JSON.stringify(message.content, null, 2)}`).join('\n\n')
      setMcpPreview({ title: prompt.displayName, content })
    })
  }

  async function chooseKnowledgeBase(id: string) {
    setSelectedKnowledgeBase(id)
    setDocuments(id ? await api<KnowledgeDocument[]>(`/api/knowledge-bases/${id}/documents`) : [])
  }

  async function uploadDocument(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const input = event.currentTarget.elements.namedItem('file') as HTMLInputElement
    const file = input.files?.[0]
    if (!file || !selectedKnowledgeBase) return
    await perform(async () => {
      const form = new FormData(); form.append('file', file)
      const response = await fetch(`/api/knowledge-bases/${selectedKnowledgeBase}/documents`, { method: 'POST', body: form })
      if (!response.ok) { const body = await response.json().catch(() => ({ message: `HTTP ${response.status}` })); throw new Error(body.message ?? '上传失败') }
      input.value = ''; await chooseKnowledgeBase(selectedKnowledgeBase)
      setNotice('文档解析、切分和向量化已完成。')
    })
  }

  async function deleteDocument(documentId: string) {
    if (!window.confirm('确认删除该文档及其全部向量片段？')) return
    await perform(async () => {
      const response = await fetch(`/api/knowledge-bases/${selectedKnowledgeBase}/documents/${documentId}`, { method: 'DELETE' })
      if (!response.ok) throw new Error('删除文档失败')
      await chooseKnowledgeBase(selectedKnowledgeBase); setNotice('文档及向量片段已删除。')
    })
  }

  async function reindexKnowledgeBase() {
    if (!selectedKnowledgeBase || !window.confirm('确认使用当前 embedding 配置重新解析并索引此知识库？原文件不会删除。')) return
    await perform(async () => {
      const result = await api<ReindexResult>(`/api/knowledge-bases/${selectedKnowledgeBase}/reindex`, { method: 'POST' })
      await chooseKnowledgeBase(selectedKnowledgeBase)
      setNotice(`索引重建完成：${result.embedding}，${result.documentCount} 份文档，${result.chunkCount} 个 chunks。`)
    })
  }

  async function submitAgent(event: FormEvent) {
    event.preventDefault()
    await perform(async () => {
      const editing = editingAgentId
      const payload = { ...agentForm, knowledgeBaseId: agentForm.knowledgeBaseId || null }
      await api(editing ? `/api/agents/${editing}` : '/api/agents', { method: editing ? 'PUT' : 'POST', body: JSON.stringify(payload) })
      setEditingAgentId(undefined); setAgentForm({ ...emptyAgent, modelProfileId: models[0]?.id ?? '' }); await refresh()
      setNotice(editing ? 'Agent 草稿已更新；请发布新版本使修改生效。' : 'Agent 草稿已创建。发布后会生成不可变版本。')
    })
  }

  function editAgent(agent: AgentDefinition) {
    setEditingAgentId(agent.id)
    setAgentForm({ name: agent.name, description: agent.description, modelProfileId: agent.draftModelProfileId, knowledgeBaseId: agent.draftKnowledgeBaseId ?? '', systemPrompt: agent.draftSystemPrompt, toolNames: [...agent.draftToolNames] })
    window.scrollTo({ top: 0, behavior: 'smooth' })
    setNotice(`正在编辑“${agent.name}”的草稿配置。`)
  }

  function cancelAgentEdit() {
    setEditingAgentId(undefined)
    setAgentForm({ ...emptyAgent, modelProfileId: models[0]?.id ?? '' })
    setNotice('已取消编辑。')
  }

  async function publish(agentId: string) {
    await perform(async () => {
      const version = await api<AgentVersion>(`/api/agents/${agentId}/publish`, { method: 'POST' })
      await refresh(); setSelectedVersion(version.id)
      setNotice(`已发布 v${version.versionNumber}，模型与提示词快照已锁定。`)
    })
  }

  async function sendMessage(event: FormEvent) {
    event.preventDefault()
    const input = chatInput.trim()
    if (!input || !selectedVersion || busy) return
    setChatInput(''); setMessages((current) => [...current, { role: 'user', content: input }, { role: 'assistant', content: '' }]); setSources([]); setRunSteps([]); setPendingApproval(undefined); setCurrentRunId(undefined); setBusy(true); setNotice('')
    try {
      const response = await fetch('/api/chat/stream', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify({ agentVersionId: selectedVersion, conversationId, message: input }) })
      if (!response.ok || !response.body) {
        const body = await response.json().catch(() => ({ message: `HTTP ${response.status}` }))
        throw new Error(body.message ?? '无法建立流式连接')
      }
      await readSse(response.body, (eventName, data) => {
        if (eventName === 'run') { setConversationId(String(data.conversationId)); setCurrentRunId(String(data.runId)) }
        if (eventName === 'sources') setSources((data.items as RagSource[]) ?? [])
        if (eventName === 'step') setRunSteps((current) => [...current, data as RunStep])
        if (eventName === 'approval_required') setPendingApproval(data as ApprovalRequest)
        if (eventName === 'delta') setMessages((current) => current.map((message, index) => index === current.length - 1 ? { ...message, content: message.content + String(data.content ?? '') } : message))
        if (eventName === 'terminated') {
          const text = String(data.message ?? '运行已停止')
          setMessages((current) => current.map((message, index) => index === current.length - 1 && !message.content ? { ...message, content: text } : message))
          setNotice(`${String(data.status)}：${text}`)
        }
        if (eventName === 'error') throw new Error(String(data.message ?? '模型调用失败'))
      })
    } catch (error) {
      const text = error instanceof Error ? error.message : '模型调用失败'
      setMessages((current) => current.map((message, index) => index === current.length - 1 && !message.content ? { ...message, content: `调用失败：${text}` } : message)); setNotice(text)
    } finally {
      setBusy(false); setPendingApproval(undefined); setCurrentRunId(undefined)
      api<RunSummary[]>('/api/runs?limit=50').then(setRunHistory).catch(() => undefined)
    }
  }

  async function cancelCurrentRun() {
    if (!currentRunId || cancelBusy) return
    setCancelBusy(true)
    try {
      await api<AgentRun>(`/api/runs/${currentRunId}/cancel`, { method: 'POST', body: '{}' })
      setNotice('取消请求已提交，正在停止模型或工具调用。')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '取消运行失败')
    } finally { setCancelBusy(false) }
  }

  async function openRun(id: string) {
    try {
      const [run, auditEvents] = await Promise.all([api<AgentRun>(`/api/runs/${id}`), api<AuditEvent[]>(`/api/audit-events?runId=${encodeURIComponent(id)}`)])
      setSelectedRun(run); setSelectedAuditEvents(auditEvents)
    }
    catch (error) { setNotice(error instanceof Error ? error.message : '读取运行详情失败') }
  }

  async function decideApproval(approved: boolean) {
    if (!pendingApproval || approvalBusy) return
    setApprovalBusy(true)
    try {
      await api(`/api/approvals/${pendingApproval.id}/${approved ? 'approve' : 'reject'}`, { method: 'POST', body: '{}' })
      setNotice(approved ? '已批准，平台将按展示的原参数执行一次。' : '已拒绝，工具不会执行。')
      setPendingApproval(undefined)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '审批操作失败')
    } finally { setApprovalBusy(false) }
  }

  function switchVersion(id: string) { setSelectedVersion(id); setConversationId(undefined); setMessages([]); setSources([]); setRunSteps([]); setPendingApproval(undefined) }

  return <div className="app-shell">
    <aside className="sidebar">
      <div><p className="eyebrow">LOCAL AGENT STUDIO</p><h1>Agent<br />Studio</h1></div>
      <nav>
        <button className={view === 'models' ? 'active' : ''} onClick={() => setView('models')}><span>01</span>模型配置</button>
        <button className={view === 'knowledge' ? 'active' : ''} onClick={() => setView('knowledge')}><span>02</span>知识库</button>
        <button className={view === 'mcp' ? 'active' : ''} onClick={() => setView('mcp')}><span>03</span>MCP 连接</button>
        <button className={view === 'agents' ? 'active' : ''} onClick={() => setView('agents')}><span>04</span>Agent Builder</button>
        <button className={view === 'chat' ? 'active' : ''} onClick={() => setView('chat')}><span>05</span>对话测试台</button>
        <button className={view === 'runs' ? 'active' : ''} onClick={() => { setView('runs'); void refresh() }}><span>06</span>运行记录</button>
      </nav>
      <div className={`connection connection--${backendState}`}><i />{backendState === 'online' ? '后端已连接' : backendState === 'checking' ? '正在连接' : '后端未连接'}</div>
    </aside>
    <main className="workspace">
      {notice && <div className="notice">{notice}<button onClick={() => setNotice('')}>×</button></div>}
      {view === 'models' && <section><PageHeader number="01" title="模型配置" description="仅保存连接参数和密钥环境变量名，不保存密钥明文。" /><div className="two-column">
        <form className="panel form" onSubmit={submitModel}><h2>新增模型连接</h2>
          <Field label="显示名称"><input required value={modelForm.name} onChange={(e) => setModelForm({ ...modelForm, name: e.target.value })} placeholder="例如：OpenAI 主模型" /></Field>
          <Field label="兼容 API 地址"><input required type="url" value={modelForm.baseUrl} onChange={(e) => setModelForm({ ...modelForm, baseUrl: e.target.value })} /></Field>
          <Field label="模型名称"><input required value={modelForm.modelName} onChange={(e) => setModelForm({ ...modelForm, modelName: e.target.value })} placeholder="例如：gpt-4.1-mini" /></Field>
          <Field label="密钥环境变量"><input required value={modelForm.apiKeyEnv} onChange={(e) => setModelForm({ ...modelForm, apiKeyEnv: e.target.value })} /></Field>
          <Field label={`温度 ${modelForm.temperature}`}><input type="range" min="0" max="2" step="0.1" value={modelForm.temperature} onChange={(e) => setModelForm({ ...modelForm, temperature: Number(e.target.value) })} /></Field>
          <button className="primary" disabled={busy}>保存模型配置</button>
        </form>
        <div className="panel list-panel"><h2>已配置模型 <small>{models.length}</small></h2>{models.length === 0 ? <Empty text="尚无模型配置" /> : models.map((model) => <article className="model-card" key={model.id}><div><strong>{model.name}</strong><span>{model.modelName}</span></div><code>{model.baseUrl}</code><p>密钥：{model.apiKeyEnv} · 温度 {model.temperature}</p></article>)}</div>
      </div>
      </section>}
      {view === 'knowledge' && <section><PageHeader number="02" title="知识库" description="文档保存在本机，切分结果写入 pgvector；删除操作会同步清理文件和向量。" /><div className="two-column">
        <div className="panel-stack">
          <form className="panel form" onSubmit={submitKnowledgeBase}><h2>新建知识库</h2>
            <Field label="知识库名称"><input required value={knowledgeForm.name} onChange={(e) => setKnowledgeForm({ ...knowledgeForm, name: e.target.value })} placeholder="例如：科研资料库" /></Field>
            <Field label="说明"><textarea rows={4} value={knowledgeForm.description} onChange={(e) => setKnowledgeForm({ ...knowledgeForm, description: e.target.value })} /></Field>
            <button className="primary" disabled={busy}>创建知识库</button>
          </form>
          <form className="panel form" onSubmit={uploadDocument}><h2>上传文档</h2>
            <Field label="当前知识库"><select value={selectedKnowledgeBase} onChange={(e) => void chooseKnowledgeBase(e.target.value)}><option value="">请选择</option>{knowledgeBases.map((base) => <option key={base.id} value={base.id}>{base.name}</option>)}</select></Field>
            <p className="hint">支持 TXT、Markdown、PDF、DOCX 等常见文档，单文件最大 20 MB。</p>
            <input name="file" type="file" required />
            <button className="secondary" disabled={busy || !selectedKnowledgeBase}>上传并解析</button>
            <button className="ghost" type="button" disabled={busy || !selectedKnowledgeBase || documents.length === 0} onClick={() => void reindexKnowledgeBase()}>使用当前模型重建索引</button>
          </form>
        </div>
        <div className="panel list-panel"><h2>文档 <small>{documents.length}</small></h2>{documents.length === 0 ? <Empty text="选择知识库并上传第一份文档" /> : documents.map((document) => <article className="document-card" key={document.id}><div><div><strong>{document.fileName}</strong><p>{formatBytes(document.fileSize)} · {document.chunkCount} chunks</p></div><span className={`badge badge--${document.status.toLowerCase()}`}>{document.status}</span></div>{document.errorMessage && <p className="error-text">{document.errorMessage}</p>}<button className="danger" onClick={() => void deleteDocument(document.id)}>删除</button></article>)}</div>
      </div></section>}
      {view === 'mcp' && <section><PageHeader number="03" title="MCP 连接" description="连接远程 HTTP 或本机 stdio MCP Server，并统一管理发现、权限、审批、超时和审计。" /><div className="two-column">
        <form className="panel form" onSubmit={submitMcpServer}><div className="section-head"><h2>{editingMcpId ? '编辑 MCP Server' : '新增 MCP Server'}</h2><label className="file-action">导入配置<input type="file" accept="application/json,.json" onChange={(event) => void importMcpConfiguration(event)} /></label></div>
          {editingMcpId && <p className="edit-hint">修改连接配置后会自动重新同步；历史运行和已发布版本不会被改写。</p>}
          <Field label="连接方式"><select value={mcpForm.transport} onChange={(e) => setMcpForm({ ...mcpForm, transport: e.target.value as 'STREAMABLE_HTTP' | 'STDIO' })}><option value="STREAMABLE_HTTP">Streamable HTTP</option><option value="STDIO">本机 stdio 子进程</option></select></Field>
          <Field label="显示名称"><input required value={mcpForm.name} onChange={(e) => setMcpForm({ ...mcpForm, name: e.target.value })} placeholder="例如：本地研发工具" /></Field>
          {mcpForm.transport === 'STREAMABLE_HTTP' ? <>
            <p className="hint">HTTP 仅允许本机地址，远程连接必须使用 HTTPS。</p>
            <Field label="MCP Endpoint"><input required type="url" value={mcpForm.endpointUrl} onChange={(e) => setMcpForm({ ...mcpForm, endpointUrl: e.target.value })} placeholder="http://127.0.0.1:3001/mcp" /></Field>
            <Field label="Bearer Token 环境变量（可选）"><input value={mcpForm.apiKeyEnv} onChange={(e) => setMcpForm({ ...mcpForm, apiKeyEnv: e.target.value })} placeholder="例如：MCP_API_TOKEN" /></Field>
          </> : <>
            <p className="hint">平台直接启动程序，不经过命令行解释器；stdout 仅用于 MCP 消息，日志请写 stderr。</p>
            <Field label="启动程序"><input required value={mcpForm.command} onChange={(e) => setMcpForm({ ...mcpForm, command: e.target.value })} placeholder="node 或可执行文件绝对路径" /></Field>
            <Field label="启动参数（每行一个）"><textarea rows={4} value={mcpForm.argumentsText} onChange={(e) => setMcpForm({ ...mcpForm, argumentsText: e.target.value })} placeholder={'D:\\path\\to\\server.mjs'} /></Field>
            <Field label="工作目录（可选）"><input value={mcpForm.workingDirectory} onChange={(e) => setMcpForm({ ...mcpForm, workingDirectory: e.target.value })} /></Field>
            <Field label="环境变量映射（每行 子进程变量=宿主变量）"><textarea rows={3} value={mcpForm.environmentText} onChange={(e) => setMcpForm({ ...mcpForm, environmentText: e.target.value })} placeholder="SERVICE_TOKEN=MCP_SERVICE_TOKEN" /></Field>
          </>}
          <div className="form-actions"><button className="primary" disabled={busy}>{editingMcpId ? '保存并重新同步' : '保存、连接并发现工具'}</button>{editingMcpId && <button className="ghost" type="button" onClick={() => { setEditingMcpId(undefined); setMcpForm(emptyMcp) }}>取消编辑</button>}</div>
        </form>
        <div className="panel list-panel"><h2>MCP Server <small>{mcpServers.length}</small></h2>{mcpServers.length === 0 ? <Empty text="尚未连接 MCP Server" /> : mcpServers.map((server) => <article className={`mcp-card ${server.enabled ? '' : 'mcp-card--disabled'}`} key={server.id}><div className="card-head"><div><strong>{server.name}</strong><p>{server.remoteServerName ? `${server.remoteServerName} · ${server.remoteServerVersion ?? '未知版本'}` : server.transport === 'STDIO' ? '本机 stdio' : server.endpointUrl}</p></div><span className={`badge badge--${server.status.toLowerCase()}`}>{server.enabled ? server.status : 'DISABLED'}</span></div><code>{server.transport === 'STDIO' ? [server.command, ...(server.arguments ?? [])].join(' ') : server.endpointUrl}</code><p className="hint">{server.transport === 'STDIO' ? '本机子进程' : 'Streamable HTTP'} · 协议 {server.protocolVersion ?? '尚未协商'}{server.apiKeyEnv ? ` · 凭据 ${server.apiKeyEnv}` : ''}</p>{server.lastError && <p className="error-text">{server.lastError}</p>}<div className="mcp-tools">{server.tools.filter((tool) => tool.active).map((tool) => <details key={tool.publicName}><summary>{tool.displayName}<span>{tool.enabled ? tool.riskLevel : 'OFF'}</span></summary><p>{tool.description}</p><code>{tool.publicName}</code><div className="tool-policy"><label><input type="checkbox" checked={tool.enabled} onChange={(e) => void updateMcpTool(tool, { enabled: e.target.checked })} />允许 Agent 使用</label><label>风险<select value={tool.riskLevel} onChange={(e) => void updateMcpTool(tool, { riskLevel: e.target.value })}><option value="HIGH">高风险（逐次审批）</option><option value="LOW">低风险（直接执行）</option></select></label><label>超时（秒）<input type="number" min="1" max="300" value={tool.timeoutSeconds} onChange={(e) => void updateMcpTool(tool, { timeoutSeconds: Number(e.target.value) })} /></label></div></details>)}</div>{(mcpSyncEvents[server.id]?.length ?? 0) > 0 && <details className="sync-history"><summary>最近同步记录</summary>{mcpSyncEvents[server.id].slice(0, 5).map((item) => <p key={item.id}><span className={`badge badge--${item.status.toLowerCase()}`}>{item.status}</span>{new Date(item.createdAt).toLocaleString()} · T/R/P {item.toolCount}/{item.resourceCount}/{item.promptCount}{item.errorMessage ? ` · ${item.errorMessage}` : ''}</p>)}</details>}<div className="card-actions"><small>{server.lastSyncedAt ? `上次同步 ${new Date(server.lastSyncedAt).toLocaleString()}` : '尚未同步'}</small><button className="ghost" disabled={busy} onClick={() => void exportMcpConfiguration(server)}>导出</button><button className="ghost" disabled={busy} onClick={() => editMcpServer(server)}>编辑</button><button className="ghost" disabled={busy} onClick={() => void toggleMcpServer(server)}>{server.enabled ? '停用' : '启用'}</button><button className="secondary" disabled={busy || !server.enabled} onClick={() => void syncMcpServer(server.id)}>测试并同步</button><button className="danger compact" disabled={busy} onClick={() => void deleteMcpServer(server)}>删除</button></div></article>)}</div>
      </div>
        <div className="panel mcp-library"><div className="section-head"><h2>Resources 与 Prompts</h2><button className="ghost" disabled={busy} onClick={() => void Promise.all(mcpServers.map((server) => loadMcpAssets(server.id)))}>刷新目录</button></div>
          <p className="hint">Resource 可以预览或导入当前选中的知识库；Prompt 由你主动选择并填写参数，不会被模型自动执行。</p>
          {mcpPreview && <aside className="mcp-preview"><div><strong>{mcpPreview.title}</strong><button className="ghost" onClick={() => setMcpPreview(undefined)}>关闭</button></div><pre>{mcpPreview.content}</pre></aside>}
          {mcpServers.flatMap((server) => {
            const assets = mcpAssets[server.id] ?? { resources: [], prompts: [] }
            return [...assets.resources.filter((item) => item.active).map((resource) => <article className="mcp-asset" key={resource.publicId}><div><b>{resource.displayName}</b><small>RESOURCE · {server.name} · {resource.mimeType ?? '未知类型'}</small><p>{resource.description || resource.uri}</p></div><div><button className="ghost" onClick={() => void previewMcpResource(server.id, resource)}>预览</button><button className="secondary" onClick={() => void importMcpResource(server.id, resource)}>导入知识库</button></div></article>), ...assets.prompts.filter((item) => item.active).map((prompt) => <article className="mcp-asset" key={prompt.publicName}><div><b>{prompt.displayName}</b><small>PROMPT · {server.name} · {prompt.arguments.length} 个参数</small><p>{prompt.description || prompt.remoteName}</p></div><button className="secondary" onClick={() => void getMcpPrompt(server.id, prompt)}>填写参数并生成</button></article>)]
          }).length === 0 ? <Empty text="同步支持 resources 或 prompts 的 MCP Server 后会显示在这里" /> : mcpServers.flatMap((server) => {
            const assets = mcpAssets[server.id] ?? { resources: [], prompts: [] }
            return [...assets.resources.filter((item) => item.active).map((resource) => <article className="mcp-asset" key={resource.publicId}><div><b>{resource.displayName}</b><small>RESOURCE · {server.name} · {resource.mimeType ?? '未知类型'}</small><p>{resource.description || resource.uri}</p></div><div><button className="ghost" onClick={() => void previewMcpResource(server.id, resource)}>预览</button><button className="secondary" onClick={() => void importMcpResource(server.id, resource)}>导入知识库</button></div></article>), ...assets.prompts.filter((item) => item.active).map((prompt) => <article className="mcp-asset" key={prompt.publicName}><div><b>{prompt.displayName}</b><small>PROMPT · {server.name} · {prompt.arguments.length} 个参数</small><p>{prompt.description || prompt.remoteName}</p></div><button className="secondary" onClick={() => void getMcpPrompt(server.id, prompt)}>填写参数并生成</button></article>)]
          })}
        </div>
      </section>}
      {view === 'agents' && <section><PageHeader number="04" title="Agent Builder" description="编辑草稿，然后发布不可变版本；历史运行始终绑定原版本。" /><div className="two-column">
        <form className="panel form" onSubmit={submitAgent}><h2>{editingAgentId ? '编辑 Agent 草稿' : '创建 Agent 草稿'}</h2>
          {editingAgentId && <p className="edit-hint">保存只会更新草稿，已发布版本不会改变。</p>}
          <Field label="Agent 名称"><input required value={agentForm.name} onChange={(e) => setAgentForm({ ...agentForm, name: e.target.value })} /></Field>
          <Field label="简介"><input value={agentForm.description} onChange={(e) => setAgentForm({ ...agentForm, description: e.target.value })} /></Field>
          <Field label="模型配置"><select required value={agentForm.modelProfileId} onChange={(e) => setAgentForm({ ...agentForm, modelProfileId: e.target.value })}><option value="">请选择</option>{models.map((model) => <option key={model.id} value={model.id}>{model.name} · {model.modelName}</option>)}</select></Field>
          <Field label="知识库（可选）"><select value={agentForm.knowledgeBaseId} onChange={(e) => setAgentForm({ ...agentForm, knowledgeBaseId: e.target.value })}><option value="">不使用知识库</option>{knowledgeBases.map((base) => <option key={base.id} value={base.id}>{base.name}</option>)}</select></Field>
          <Field label="工具（可选）"><div className="tool-options">{tools.length === 0 ? <p className="hint">暂无可用工具</p> : tools.map((tool) => <label className="tool-option" key={tool.name}><input type="checkbox" checked={agentForm.toolNames.includes(tool.name)} onChange={(e) => setAgentForm({ ...agentForm, toolNames: e.target.checked ? [...agentForm.toolNames, tool.name] : agentForm.toolNames.filter((name) => name !== tool.name) })} /><span><b>{tool.displayName}</b><small>{tool.source} · {tool.capability} · {tool.riskLevel} · {tool.timeoutSeconds}s</small><em>{tool.description}</em></span></label>)}</div></Field>
          <Field label="系统提示词"><textarea required rows={8} value={agentForm.systemPrompt} onChange={(e) => setAgentForm({ ...agentForm, systemPrompt: e.target.value })} placeholder="定义 Agent 的身份、目标和边界" /></Field>
          <div className="form-actions"><button className="primary" disabled={busy || models.length === 0}>{editingAgentId ? '保存草稿修改' : '创建草稿'}</button>{editingAgentId && <button className="ghost" type="button" disabled={busy} onClick={cancelAgentEdit}>取消编辑</button>}</div>
        </form>
        <div className="panel list-panel"><h2>Agent 列表 <small>{agents.length}</small></h2>{agents.length === 0 ? <Empty text="先配置模型，再创建 Agent" /> : agents.map((agent) => <article className="agent-card" key={agent.id}><div className="card-head"><div><strong>{agent.name}</strong><p>{agent.description || '暂无简介'}</p></div><span className={`badge badge--${(agent.status ?? 'DRAFT').toLowerCase()}`}>{agent.status ?? 'DRAFT'}</span></div>{agent.draftToolNames.length > 0 && <div className="tool-chips">{agent.draftToolNames.map((name) => <span key={name}>{name}</span>)}</div>}<div className="agent-meta"><span>最新版本</span><b>{agent.latestVersionNumber ? `v${agent.latestVersionNumber}` : '未发布'}</b></div><div className="card-actions"><button className="ghost" disabled={busy} onClick={() => editAgent(agent)}>编辑草稿</button><button className="secondary" disabled={busy} onClick={() => void publish(agent.id)}>发布新版本</button></div></article>)}</div>
      </div></section>}
      {view === 'chat' && <section><PageHeader number="05" title="对话测试台" description="选择已发布版本；MCP 工具与内置工具共享审批和运行记录。" />
        <div className="chat-toolbar"><label>Agent 版本<select value={selectedVersion} onChange={(e) => switchVersion(e.target.value)}><option value="">选择已发布版本</option>{versionLabels.map((version) => <option key={version.id} value={version.id}>{version.label}</option>)}</select></label><span>{conversationId ? `会话 ${conversationId.slice(0, 8)}` : '新会话'}</span><button className="ghost" onClick={() => { setConversationId(undefined); setMessages([]); setSources([]); setRunSteps([]); setPendingApproval(undefined) }}>清空会话</button></div>
        <div className="chat-panel"><div className="messages">{pendingApproval && <aside className="approval-card"><strong>等待高风险操作审批</strong><p>工具：<code>{pendingApproval.toolName}</code> · {pendingApproval.capability}/{pendingApproval.riskLevel}</p><p>目标：{pendingApproval.targetEnvironment}</p><pre>{pendingApproval.argumentsJson}</pre><small>参数摘要：{pendingApproval.argumentsSha256.slice(0, 16)}… · {new Date(pendingApproval.expiresAt).toLocaleTimeString()} 前有效</small><div><button className="danger" disabled={approvalBusy} onClick={() => void decideApproval(false)}>拒绝</button><button className="primary" disabled={approvalBusy} onClick={() => void decideApproval(true)}>批准执行一次</button></div></aside>}{sources.length > 0 && <aside className="sources"><strong>本次检索来源</strong>{sources.map((source) => <details key={`${source.documentId}-${source.chunkIndex}`}><summary>{source.fileName} · chunk {source.chunkIndex} · {Math.round(source.score * 100)}%</summary><p>{source.content}</p></details>)}</aside>}{runSteps.length > 0 && <aside className="run-steps"><strong>运行步骤</strong>{runSteps.map((step) => <details key={step.id} open={step.stepType === 'TOOL_RESULT' || step.stepType.startsWith('APPROVAL')}><summary>#{step.stepNumber} {step.stepType}{step.toolName ? ` · ${step.toolName}` : ''}<span className={`step-status step-status--${step.status.toLowerCase()}`}>{step.status}</span></summary>{step.inputJson && <pre>输入：{step.inputJson}</pre>}{step.outputText && <pre>输出：{step.outputText}</pre>}{step.durationMs != null && <small>{step.durationMs} ms</small>}</details>)}</aside>}{messages.length === 0 ? <Empty text={versions.length ? '选择版本并发送第一条消息' : '请先发布一个 Agent 版本'} /> : messages.map((message, index) => <article className={`message message--${message.role}`} key={index}><span>{message.role === 'user' ? 'YOU' : 'AGENT'}</span><p>{message.content || <i className="typing">正在生成</i>}</p></article>)}</div>
          <form className="composer" onSubmit={sendMessage}><textarea rows={3} value={chatInput} onChange={(e) => setChatInput(e.target.value)} placeholder="输入测试问题……" onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }} /><div className="composer-actions"><button className="primary" disabled={busy || !selectedVersion || !chatInput.trim()}>{busy ? '生成中' : '发送'}</button>{busy && currentRunId && <button className="danger" type="button" disabled={cancelBusy} onClick={() => void cancelCurrentRun()}>{cancelBusy ? '停止中' : '停止运行'}</button>}</div></form>
        </div>
      </section>}
      {view === 'runs' && <section><PageHeader number="06" title="运行记录" description="查看每次 AgentRun 的最终状态、耗时、错误和完整步骤。" />
        <div className="two-column run-history-layout"><div className="panel list-panel"><div className="section-head"><h2>最近运行 <small>{runHistory.length}</small></h2><button className="ghost" onClick={() => void refresh()}>刷新</button></div>{runHistory.length === 0 ? <Empty text="尚无运行记录" /> : runHistory.map((run) => <button className={`run-card ${selectedRun?.id === run.id ? 'active' : ''}`} key={run.id} onClick={() => void openRun(run.id)}><div><strong>{run.id.slice(0, 8)}</strong><span className={`badge badge--${run.status.toLowerCase()}`}>{run.status}</span></div><p>{new Date(run.startedAt).toLocaleString()} · {run.stepCount} 步</p>{run.errorMessage && <small>{run.errorMessage}</small>}</button>)}</div>
          <div className="panel run-detail">{!selectedRun ? <Empty text="选择一条运行查看完整步骤" /> : <><div className="card-head"><div><strong>运行 {selectedRun.id.slice(0, 8)}</strong><p>会话 {selectedRun.conversationId.slice(0, 8)}</p></div><span className={`badge badge--${selectedRun.status.toLowerCase()}`}>{selectedRun.status}</span></div><dl><div><dt>AgentVersion</dt><dd>{selectedRun.agentVersionId}</dd></div><div><dt>开始</dt><dd>{new Date(selectedRun.startedAt).toLocaleString()}</dd></div>{selectedRun.completedAt && <div><dt>结束</dt><dd>{new Date(selectedRun.completedAt).toLocaleString()}</dd></div>}</dl>{selectedRun.errorMessage && <p className="run-error">{selectedRun.errorMessage}</p>}<aside className="run-steps"><strong>完整步骤</strong>{selectedRun.steps.map((step) => <details key={step.id}><summary>#{step.stepNumber} {step.stepType}{step.toolName ? ` · ${step.toolName}` : ''}<span className={`step-status step-status--${step.status.toLowerCase()}`}>{step.status}</span></summary>{step.inputJson && <pre>输入：{step.inputJson}</pre>}{step.outputText && <pre>输出：{step.outputText}</pre>}{step.durationMs != null && <small>{step.durationMs} ms</small>}</details>)}</aside><aside className="audit-events"><strong>安全审计</strong>{selectedAuditEvents.length === 0 ? <p>该运行没有工具审计事件</p> : selectedAuditEvents.map((event) => <article key={event.id}><div><b>{event.eventType}</b><span className={`step-status step-status--${event.status.toLowerCase()}`}>{event.status}</span></div><small>{new Date(event.createdAt).toLocaleTimeString()} · {event.toolName} · {event.capability}/{event.riskLevel}</small>{event.argumentsSha256 && <code>参数摘要 {event.argumentsSha256.slice(0, 16)}…</code>}{event.details && <p>{event.details}</p>}</article>)}</aside></>}</div></div>
      </section>}
    </main>
  </div>
}

function PageHeader({ number, title, description }: { number: string; title: string; description: string }) { return <header className="page-header"><span>{number}</span><div><h2>{title}</h2><p>{description}</p></div></header> }
function Field({ label, children }: { label: string; children: React.ReactNode }) { return <label className="field"><span>{label}</span>{children}</label> }
function Empty({ text }: { text: string }) { return <div className="empty"><i>◇</i><p>{text}</p></div> }
function formatBytes(bytes: number) { return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB` }

async function readSse(stream: ReadableStream<Uint8Array>, onEvent: (name: string, data: Record<string, unknown>) => void) {
  const reader = stream.getReader(); const decoder = new TextDecoder(); let buffer = ''
  while (true) {
    const { done, value } = await reader.read(); buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
    const blocks = buffer.split('\n\n'); buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      let name = 'message'; const dataLines: string[] = []
      for (const line of block.split('\n')) { if (line.startsWith('event:')) name = line.slice(6).trim(); if (line.startsWith('data:')) dataLines.push(line.slice(5).trim()) }
      if (dataLines.length) onEvent(name, JSON.parse(dataLines.join('\n')) as Record<string, unknown>)
    }
    if (done) break
  }
}

export default App
