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
type McpTool = { publicName: string; remoteName: string; displayName: string; description: string; capability: string; riskLevel: string; timeoutSeconds: number; active: boolean; schemaSha256: string }
type McpServer = { id: string; name: string; endpointUrl: string; apiKeyEnv?: string; status: string; protocolVersion?: string; remoteServerName?: string; remoteServerVersion?: string; lastError?: string; lastSyncedAt?: string; tools: McpTool[] }

const emptyModel = { name: '', provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.openai.com/v1', modelName: '', apiKeyEnv: 'OPENAI_API_KEY', temperature: 0.7 }
const emptyAgent = { name: '', description: '', modelProfileId: '', knowledgeBaseId: '', systemPrompt: '', toolNames: [] as string[] }
const emptyMcp = { name: '', endpointUrl: 'http://127.0.0.1:3001/mcp', apiKeyEnv: '' }

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
      const created = await api<McpServer>('/api/mcp/servers', { method: 'POST', body: JSON.stringify({ ...mcpForm, apiKeyEnv: mcpForm.apiKeyEnv || null }) })
      setNotice('MCP Server 已保存，正在连接并发现工具……')
      try { await api(`/api/mcp/servers/${created.id}/sync`, { method: 'POST', body: '{}' }) }
      catch (error) { await refresh(); throw error }
      setMcpForm(emptyMcp); await refresh()
      setNotice('MCP 连接成功，发现的工具已进入 Agent Builder；首次绑定仍需发布新版本。')
    })
  }

  async function syncMcpServer(id: string) {
    await perform(async () => {
      const result = await api<{ toolCount: number; remoteServerName: string }>(`/api/mcp/servers/${id}/sync`, { method: 'POST', body: '{}' })
      await refresh(); setNotice(`MCP 同步完成：${result.remoteServerName}，发现 ${result.toolCount} 个工具。`)
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
      </div></section>}
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
      {view === 'mcp' && <section><PageHeader number="03" title="MCP 连接" description="连接 Streamable HTTP MCP Server，发现工具后统一交给平台审批、超时和审计。" /><div className="two-column">
        <form className="panel form" onSubmit={submitMcpServer}><h2>新增 MCP Server</h2>
          <p className="hint">当前支持 MCP 2025-06-18 Streamable HTTP。HTTP 仅允许本机地址，远程连接必须使用 HTTPS。</p>
          <Field label="显示名称"><input required value={mcpForm.name} onChange={(e) => setMcpForm({ ...mcpForm, name: e.target.value })} placeholder="例如：本地研发工具" /></Field>
          <Field label="MCP Endpoint"><input required type="url" value={mcpForm.endpointUrl} onChange={(e) => setMcpForm({ ...mcpForm, endpointUrl: e.target.value })} placeholder="http://127.0.0.1:3001/mcp" /></Field>
          <Field label="Bearer Token 环境变量（可选）"><input value={mcpForm.apiKeyEnv} onChange={(e) => setMcpForm({ ...mcpForm, apiKeyEnv: e.target.value })} placeholder="例如：MCP_API_TOKEN" /></Field>
          <button className="primary" disabled={busy}>保存、连接并发现工具</button>
        </form>
        <div className="panel list-panel"><h2>MCP Server <small>{mcpServers.length}</small></h2>{mcpServers.length === 0 ? <Empty text="尚未连接 MCP Server" /> : mcpServers.map((server) => <article className="mcp-card" key={server.id}><div className="card-head"><div><strong>{server.name}</strong><p>{server.remoteServerName ? `${server.remoteServerName} · ${server.remoteServerVersion ?? '未知版本'}` : server.endpointUrl}</p></div><span className={`badge badge--${server.status.toLowerCase()}`}>{server.status}</span></div><code>{server.endpointUrl}</code><p className="hint">协议 {server.protocolVersion ?? '尚未协商'}{server.apiKeyEnv ? ` · 凭据 ${server.apiKeyEnv}` : ' · 无鉴权'}</p>{server.lastError && <p className="error-text">{server.lastError}</p>}<div className="mcp-tools">{server.tools.filter((tool) => tool.active).map((tool) => <details key={tool.publicName}><summary>{tool.displayName}<span>{tool.riskLevel}</span></summary><p>{tool.description}</p><code>{tool.publicName}</code></details>)}</div><div className="card-actions"><small>{server.lastSyncedAt ? `上次同步 ${new Date(server.lastSyncedAt).toLocaleString()}` : '尚未同步'}</small><button className="secondary" disabled={busy} onClick={() => void syncMcpServer(server.id)}>测试并同步</button></div></article>)}</div>
      </div></section>}
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
