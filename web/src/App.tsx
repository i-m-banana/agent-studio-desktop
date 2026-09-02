import { FormEvent, useEffect, useMemo, useState } from 'react'

type BackendState = 'checking' | 'online' | 'offline'
type View = 'models' | 'knowledge' | 'agents' | 'chat'
type ModelProfile = { id: string; name: string; provider: string; baseUrl: string; modelName: string; apiKeyEnv: string; temperature: number }
type KnowledgeBase = { id: string; name: string; description: string }
type KnowledgeDocument = { id: string; fileName: string; fileSize: number; status: string; chunkCount: number; errorMessage?: string }
type ReindexResult = { knowledgeBaseId: string; embedding: string; documentCount: number; chunkCount: number }
type RagSource = { documentId: string; fileName: string; chunkIndex: number; content: string; score: number }
type AgentDefinition = { id: string; name: string; description: string; draftModelProfileId: string; draftKnowledgeBaseId?: string; draftSystemPrompt: string; latestVersionNumber: number; status: 'DRAFT' | 'PUBLISHED' }
type AgentVersion = { id: string; agentDefinitionId: string; versionNumber: number; modelProfileName: string; modelName: string; systemPrompt: string }
type ChatMessage = { role: 'user' | 'assistant'; content: string }

const emptyModel = { name: '', provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.openai.com/v1', modelName: '', apiKeyEnv: 'OPENAI_API_KEY', temperature: 0.7 }
const emptyAgent = { name: '', description: '', modelProfileId: '', knowledgeBaseId: '', systemPrompt: '' }

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
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [selectedKnowledgeBase, setSelectedKnowledgeBase] = useState('')
  const [knowledgeForm, setKnowledgeForm] = useState({ name: '', description: '' })
  const [versions, setVersions] = useState<AgentVersion[]>([])
  const [modelForm, setModelForm] = useState(emptyModel)
  const [agentForm, setAgentForm] = useState(emptyAgent)
  const [selectedVersion, setSelectedVersion] = useState('')
  const [conversationId, setConversationId] = useState<string>()
  const [chatInput, setChatInput] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [sources, setSources] = useState<RagSource[]>([])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')

  const versionLabels = useMemo(() => {
    const names = new Map(agents.map((agent) => [agent.id, agent.name]))
    return versions.map((version) => ({ ...version, label: `${names.get(version.agentDefinitionId) ?? 'Agent'} · v${version.versionNumber} · ${version.modelName}` }))
  }, [agents, versions])

  async function refresh() {
    try {
      const [nextModels, nextAgents, nextBases] = await Promise.all([api<ModelProfile[]>('/api/models'), api<AgentDefinition[]>('/api/agents'), api<KnowledgeBase[]>('/api/knowledge-bases')])
      const groups = await Promise.all(nextAgents.map((agent) => api<AgentVersion[]>(`/api/agents/${agent.id}/versions`)))
      setModels(nextModels); setAgents(nextAgents); setVersions(groups.flat()); setKnowledgeBases(nextBases); setBackendState('online')
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
      await api('/api/agents', { method: 'POST', body: JSON.stringify(agentForm) })
      setAgentForm({ ...emptyAgent, modelProfileId: models[0]?.id ?? '' }); await refresh()
      setNotice('Agent 草稿已创建。发布后会生成不可变版本。')
    })
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
    setChatInput(''); setMessages((current) => [...current, { role: 'user', content: input }, { role: 'assistant', content: '' }]); setSources([]); setBusy(true); setNotice('')
    try {
      const response = await fetch('/api/chat/stream', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify({ agentVersionId: selectedVersion, conversationId, message: input }) })
      if (!response.ok || !response.body) {
        const body = await response.json().catch(() => ({ message: `HTTP ${response.status}` }))
        throw new Error(body.message ?? '无法建立流式连接')
      }
      await readSse(response.body, (eventName, data) => {
        if (eventName === 'run') setConversationId(String(data.conversationId))
        if (eventName === 'sources') setSources((data.items as RagSource[]) ?? [])
        if (eventName === 'delta') setMessages((current) => current.map((message, index) => index === current.length - 1 ? { ...message, content: message.content + String(data.content ?? '') } : message))
        if (eventName === 'error') throw new Error(String(data.message ?? '模型调用失败'))
      })
    } catch (error) {
      const text = error instanceof Error ? error.message : '模型调用失败'
      setMessages((current) => current.map((message, index) => index === current.length - 1 && !message.content ? { ...message, content: `调用失败：${text}` } : message)); setNotice(text)
    } finally { setBusy(false) }
  }

  function switchVersion(id: string) { setSelectedVersion(id); setConversationId(undefined); setMessages([]); setSources([]) }

  return <div className="app-shell">
    <aside className="sidebar">
      <div><p className="eyebrow">LOCAL AGENT STUDIO</p><h1>Agent<br />Studio</h1></div>
      <nav>
        <button className={view === 'models' ? 'active' : ''} onClick={() => setView('models')}><span>01</span>模型配置</button>
        <button className={view === 'knowledge' ? 'active' : ''} onClick={() => setView('knowledge')}><span>02</span>知识库</button>
        <button className={view === 'agents' ? 'active' : ''} onClick={() => setView('agents')}><span>03</span>Agent Builder</button>
        <button className={view === 'chat' ? 'active' : ''} onClick={() => setView('chat')}><span>04</span>对话测试台</button>
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
      {view === 'agents' && <section><PageHeader number="03" title="Agent Builder" description="编辑草稿，然后发布不可变版本；历史运行始终绑定原版本。" /><div className="two-column">
        <form className="panel form" onSubmit={submitAgent}><h2>创建 Agent 草稿</h2>
          <Field label="Agent 名称"><input required value={agentForm.name} onChange={(e) => setAgentForm({ ...agentForm, name: e.target.value })} /></Field>
          <Field label="简介"><input value={agentForm.description} onChange={(e) => setAgentForm({ ...agentForm, description: e.target.value })} /></Field>
          <Field label="模型配置"><select required value={agentForm.modelProfileId} onChange={(e) => setAgentForm({ ...agentForm, modelProfileId: e.target.value })}><option value="">请选择</option>{models.map((model) => <option key={model.id} value={model.id}>{model.name} · {model.modelName}</option>)}</select></Field>
          <Field label="知识库（可选）"><select value={agentForm.knowledgeBaseId} onChange={(e) => setAgentForm({ ...agentForm, knowledgeBaseId: e.target.value })}><option value="">不使用知识库</option>{knowledgeBases.map((base) => <option key={base.id} value={base.id}>{base.name}</option>)}</select></Field>
          <Field label="系统提示词"><textarea required rows={8} value={agentForm.systemPrompt} onChange={(e) => setAgentForm({ ...agentForm, systemPrompt: e.target.value })} placeholder="定义 Agent 的身份、目标和边界" /></Field>
          <button className="primary" disabled={busy || models.length === 0}>创建草稿</button>
        </form>
        <div className="panel list-panel"><h2>Agent 列表 <small>{agents.length}</small></h2>{agents.length === 0 ? <Empty text="先配置模型，再创建 Agent" /> : agents.map((agent) => <article className="agent-card" key={agent.id}><div className="card-head"><div><strong>{agent.name}</strong><p>{agent.description || '暂无简介'}</p></div><span className={`badge badge--${(agent.status ?? 'DRAFT').toLowerCase()}`}>{agent.status ?? 'DRAFT'}</span></div><div className="agent-meta"><span>最新版本</span><b>{agent.latestVersionNumber ? `v${agent.latestVersionNumber}` : '未发布'}</b></div><button className="secondary" disabled={busy} onClick={() => void publish(agent.id)}>发布新版本</button></article>)}</div>
      </div></section>}
      {view === 'chat' && <section><PageHeader number="04" title="对话测试台" description="选择已发布版本；绑定知识库的版本会展示本次检索来源。" />
        <div className="chat-toolbar"><label>Agent 版本<select value={selectedVersion} onChange={(e) => switchVersion(e.target.value)}><option value="">选择已发布版本</option>{versionLabels.map((version) => <option key={version.id} value={version.id}>{version.label}</option>)}</select></label><span>{conversationId ? `会话 ${conversationId.slice(0, 8)}` : '新会话'}</span><button className="ghost" onClick={() => { setConversationId(undefined); setMessages([]); setSources([]) }}>清空会话</button></div>
        <div className="chat-panel"><div className="messages">{sources.length > 0 && <aside className="sources"><strong>本次检索来源</strong>{sources.map((source) => <details key={`${source.documentId}-${source.chunkIndex}`}><summary>{source.fileName} · chunk {source.chunkIndex} · {Math.round(source.score * 100)}%</summary><p>{source.content}</p></details>)}</aside>}{messages.length === 0 ? <Empty text={versions.length ? '选择版本并发送第一条消息' : '请先发布一个 Agent 版本'} /> : messages.map((message, index) => <article className={`message message--${message.role}`} key={index}><span>{message.role === 'user' ? 'YOU' : 'AGENT'}</span><p>{message.content || <i className="typing">正在生成</i>}</p></article>)}</div>
          <form className="composer" onSubmit={sendMessage}><textarea rows={3} value={chatInput} onChange={(e) => setChatInput(e.target.value)} placeholder="输入测试问题……" onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }} /><button className="primary" disabled={busy || !selectedVersion || !chatInput.trim()}>{busy ? '生成中' : '发送'}</button></form>
        </div>
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
