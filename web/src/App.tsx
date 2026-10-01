import { FormEvent, useEffect, useMemo, useState } from 'react'
import { MarkdownMessage } from './MarkdownMessage'
import { releaseImageRequest } from './releaseImage'
import { databaseBaselineRequest, mergeBaselineEvidence } from './databaseBaseline'
import { fixedToolReceipt, fixedToolSucceeded, isBaselineRegistrationReceipt, workflowReadiness } from './releaseWorkflow'
import { publishReadiness, type ProductionStatus } from './publishRelease'

type BackendState = 'checking' | 'online' | 'offline'
type View = 'models' | 'knowledge' | 'mcp' | 'agents' | 'chat' | 'remote' | 'runs' | 'system'
type ModelProfile = { id: string; name: string; provider: string; baseUrl: string; modelName: string; apiKeyEnv: string; temperature: number }
type KnowledgeBase = { id: string; name: string; description: string }
type KnowledgeDocument = { id: string; fileName: string; fileSize: number; status: string; chunkCount: number; errorMessage?: string }
type ReindexResult = { knowledgeBaseId: string; embedding: string; documentCount: number; chunkCount: number }
type RagSource = { documentId: string; fileName: string; chunkIndex: number; content: string; score: number }
type ToolDefinition = { name: string; displayName: string; description: string; source: string; capability: string; riskLevel: string; timeoutSeconds: number }
type AgentDefinition = { id: string; name: string; description: string; draftModelProfileId: string; draftKnowledgeBaseId?: string; draftSystemPrompt: string; draftToolNames: string[]; latestVersionNumber: number; status: 'DRAFT' | 'PUBLISHED' }
type AgentVersion = { id: string; agentDefinitionId: string; versionNumber: number; modelProfileName: string; modelName: string; systemPrompt: string; toolNames: string[]; publishedAt: string; archivedAt?: string; archived: boolean; usageCount: number; deletable: boolean }
type ChatMessage = { role: 'user' | 'assistant'; content: string }
type RunStep = { id: string; stepNumber: number; stepType: string; status: string; toolName?: string; inputJson?: string; outputText?: string; durationMs?: number; observedAt?: number }
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
type ReadinessCheck = { id: string; name: string; status: 'READY' | 'WARNING' | 'FAILED'; detail: string; action: string; required: boolean }
type SystemReadiness = { application: string; version: string; status: 'READY' | 'DEGRADED' | 'NOT_READY'; timestamp: string; checks: ReadinessCheck[] }
type SecretStatus = { name: string; configured: boolean; source: 'ENVIRONMENT' | 'SECURE_STORE' | 'NONE'; usedBy: string[] }
type SshWorkspaceStatus = { host: string; port: number; username: string; remoteRoot: string; hostKeySha256: string; passwordSecret: string; configured: boolean; passwordConfigured: boolean; status: string; lastError?: string; lastTestedAt?: string }
type DeploymentProfile = { localSourceRoot: string; remoteDeployRoot: string; remoteBackupRoot: string; localComposeFile: string; composeFile: string; composeProject: string; nginxConfig: string; healthUrl: string; configured: boolean; status: string; lastError?: string; lastTestedAt?: string }
type SshFingerprint = { host: string; port: number; algorithm: string; sha256: string; warning: string }

const emptyModel = { name: '', provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.openai.com/v1', modelName: '', apiKeyEnv: 'OPENAI_API_KEY', temperature: 0.7 }
const emptyAgent = { name: '', description: '', modelProfileId: '', knowledgeBaseId: '', systemPrompt: '', toolNames: [] as string[] }
const emptyMcp = { name: '', transport: 'STREAMABLE_HTTP' as 'STREAMABLE_HTTP' | 'STDIO', endpointUrl: 'http://127.0.0.1:3001/mcp', apiKeyEnv: '', command: 'node', argumentsText: '', workingDirectory: '', environmentText: '' }
const emptySsh = { host: '', port: 22, username: '', remoteRoot: '', hostKeySha256: '', passwordSecret: 'AGENT_STUDIO_SSH_PASSWORD', password: '' }
const emptyDeployment = { localSourceRoot: 'D:/idea_work/shiguangxv', remoteDeployRoot: '/root/opt/old-things', remoteBackupRoot: '/root/opt/old-things-backups', localComposeFile: 'docker-compose.yml', composeFile: 'compose.yml', composeProject: 'old-things', nginxConfig: 'nginx.conf', healthUrl: 'http://127.0.0.1/' }

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
  const [approvalSecondsLeft, setApprovalSecondsLeft] = useState(0)
  const [approvalBusy, setApprovalBusy] = useState(false)
  const [currentRunId, setCurrentRunId] = useState<string>()
  const [cancelBusy, setCancelBusy] = useState(false)
  const [runHistory, setRunHistory] = useState<RunSummary[]>([])
  const [selectedRun, setSelectedRun] = useState<AgentRun>()
  const [selectedAuditEvents, setSelectedAuditEvents] = useState<AuditEvent[]>([])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [readiness, setReadiness] = useState<SystemReadiness>()
  const [readinessBusy, setReadinessBusy] = useState(false)
  const [secrets, setSecrets] = useState<SecretStatus[]>([])
  const [secretValues, setSecretValues] = useState<Record<string, string>>({})
  const [showHistoricalVersions, setShowHistoricalVersions] = useState(false)
  const [sshStatus, setSshStatus] = useState<SshWorkspaceStatus>()
  const [sshForm, setSshForm] = useState(emptySsh)
  const [sshFingerprint, setSshFingerprint] = useState<SshFingerprint>()
  const [deployment, setDeployment] = useState<DeploymentProfile>()
  const [deploymentForm, setDeploymentForm] = useState(emptyDeployment)

  const versionLabels = useMemo(() => {
    const names = new Map(agents.map((agent) => [agent.id, agent.name]))
    return versions.filter((version) => !version.archived && (showHistoricalVersions || agents.find((agent) => agent.id === version.agentDefinitionId)?.latestVersionNumber === version.versionNumber))
      .map((version) => ({ ...version, label: `${names.get(version.agentDefinitionId) ?? 'Agent'} · v${version.versionNumber} · ${version.modelName}${version.toolNames.length ? ` · ${version.toolNames.length} 工具` : ''}` }))
  }, [agents, versions, showHistoricalVersions])

  async function refresh() {
    try {
      const [nextModels, nextAgents, nextBases, nextTools, nextRuns, nextMcpServers, nextSsh, nextDeployment] = await Promise.all([api<ModelProfile[]>('/api/models'), api<AgentDefinition[]>('/api/agents'), api<KnowledgeBase[]>('/api/knowledge-bases'), api<ToolDefinition[]>('/api/tools'), api<RunSummary[]>('/api/runs?limit=50'), api<McpServer[]>('/api/mcp/servers'), api<SshWorkspaceStatus>('/api/ssh/workspace'), api<DeploymentProfile>('/api/ssh/deployment')])
      const [groups, nextSecrets] = await Promise.all([Promise.all(nextAgents.map((agent) => api<AgentVersion[]>(`/api/agents/${agent.id}/versions?includeArchived=true`))), api<SecretStatus[]>('/api/secrets')])
      setModels(nextModels); setAgents(nextAgents); setVersions(groups.flat()); setSecrets(nextSecrets); setKnowledgeBases(nextBases); setTools(nextTools); setRunHistory(nextRuns); setMcpServers(nextMcpServers); setBackendState('online')
      setSshStatus(nextSsh)
      setDeployment(nextDeployment)
      setDeploymentForm((current) => nextDeployment.configured ? { localSourceRoot: nextDeployment.localSourceRoot, remoteDeployRoot: nextDeployment.remoteDeployRoot, remoteBackupRoot: nextDeployment.remoteBackupRoot, localComposeFile: nextDeployment.localComposeFile, composeFile: nextDeployment.composeFile, composeProject: nextDeployment.composeProject, nginxConfig: nextDeployment.nginxConfig, healthUrl: nextDeployment.healthUrl } : current)
      setSshForm((current) => current.host ? current : { host: nextSsh.host ?? '', port: nextSsh.port || 22, username: nextSsh.username ?? '', remoteRoot: nextSsh.remoteRoot ?? '', hostKeySha256: nextSsh.hostKeySha256 ?? '', passwordSecret: nextSsh.passwordSecret || 'AGENT_STUDIO_SSH_PASSWORD', password: '' })
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

  useEffect(() => {
    if (!pendingApproval) { setApprovalSecondsLeft(0); return }
    const previousTitle = document.title
    document.title = `待审批 · ${pendingApproval.toolName}`
    const updateCountdown = () => {
      setApprovalSecondsLeft(Math.max(0, Math.ceil((new Date(pendingApproval.expiresAt).getTime() - Date.now()) / 1000)))
    }
    updateCountdown()
    const timer = window.setInterval(updateCountdown, 1000)
    return () => { window.clearInterval(timer); document.title = previousTitle }
  }, [pendingApproval])

  async function inspectReadiness() {
    setReadinessBusy(true); setNotice('')
    try { setReadiness(await api<SystemReadiness>('/api/system/readiness')); setBackendState('online') }
    catch (error) { setBackendState('offline'); setNotice(error instanceof Error ? error.message : '系统诊断失败') }
    finally { setReadinessBusy(false) }
  }

  async function perform(action: () => Promise<void>) {
    setBusy(true); setNotice('')
    try { await action() } catch (error) { setNotice(error instanceof Error ? error.message : '操作失败') } finally { setBusy(false) }
  }

  async function submitModel(event: FormEvent) {
    event.preventDefault()
    await perform(async () => {
      await api('/api/models', { method: 'POST', body: JSON.stringify(modelForm) })
      setModelForm(emptyModel); await refresh()
      setNotice('模型配置已保存。现在可以在下方“安全凭据”中保存对应密钥。')
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

  async function inspectSshFingerprint() {
    if (!sshForm.host.trim()) { setNotice('请先填写云服务器公网 IP 或域名。'); return }
    await perform(async () => {
      const result = await api<SshFingerprint>('/api/ssh/workspace/fingerprint', { method: 'POST', body: JSON.stringify({ host: sshForm.host, port: sshForm.port }) })
      setSshFingerprint(result); setSshForm((current) => ({ ...current, hostKeySha256: result.sha256 }))
      setNotice(`检测到 ${result.algorithm} 主机指纹。请先到云厂商控制台或服务器中核对，确认一致后再保存。`)
    })
  }

  async function submitSshWorkspace(event: FormEvent) {
    event.preventDefault()
    await perform(async () => {
      await api<SshWorkspaceStatus>('/api/ssh/workspace', { method: 'PUT', body: JSON.stringify({ host: sshForm.host, port: sshForm.port, username: sshForm.username, remoteRoot: sshForm.remoteRoot, hostKeySha256: sshForm.hostKeySha256, passwordSecret: sshForm.passwordSecret }) })
      if (sshForm.password.trim()) await api(`/api/secrets/${encodeURIComponent(sshForm.passwordSecret)}`, { method: 'PUT', body: JSON.stringify({ value: sshForm.password }) })
      setSshForm((current) => ({ ...current, password: '' })); await refresh()
      setNotice('SSH 远程工作区和密码已保存。请点击“测试 SSH/SFTP 连接”。')
    })
  }

  async function testSshWorkspace() {
    await perform(async () => {
      const result = await api<{ message: string }>('/api/ssh/workspace/test', { method: 'POST', body: '{}' })
      await refresh(); setNotice(result.message)
    })
  }

  async function saveDeploymentProfile(value: typeof emptyDeployment) {
    await perform(async () => {
      const saved = await api<DeploymentProfile>('/api/ssh/deployment', { method: 'PUT', body: JSON.stringify(value) })
      setDeployment(saved); setNotice('部署 Profile 已保存。请先执行只读目标检查；这不会启动或重启容器。')
    })
  }

  async function testDeploymentProfile() {
    await perform(async () => {
      const result = await api<{ message: string }>('/api/ssh/deployment/test', { method: 'POST', body: '{}' })
      setDeployment(await api<DeploymentProfile>('/api/ssh/deployment')); setNotice(result.message)
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

  async function saveSecret(name: string) {
    const value = secretValues[name]?.trim()
    if (!value) { setNotice('请输入密钥后再保存。'); return }
    await perform(async () => {
      await api<SecretStatus>(`/api/secrets/${encodeURIComponent(name)}`, { method: 'PUT', body: JSON.stringify({ value }) })
      setSecretValues((current) => ({ ...current, [name]: '' }))
      await refresh()
      setNotice(`${name} 已使用 Windows 安全存储保存；页面和接口不会回显密钥。`)
    })
  }

  async function deleteSecret(name: string) {
    if (!window.confirm(`确定删除本机保存的 ${name} 吗？依赖它的模型或 MCP 将无法调用。`)) return
    await perform(async () => {
      const response = await fetch(`/api/secrets/${encodeURIComponent(name)}`, { method: 'DELETE' })
      if (!response.ok) { const body = await response.json().catch(() => ({ message: '删除失败' })); throw new Error(body.message) }
      await refresh()
      setNotice(`${name} 的本机安全凭据已删除。`)
    })
  }

  async function changeVersionLifecycle(agent: AgentDefinition, version: AgentVersion, action: 'archive' | 'restore' | 'delete') {
    if (action === 'delete' && !window.confirm(`永久删除 ${agent.name} v${version.versionNumber}？此操作不可恢复。`)) return
    await perform(async () => {
      if (action === 'delete') {
        const response = await fetch(`/api/agents/${agent.id}/versions/${version.id}`, { method: 'DELETE' })
        if (!response.ok) { const body = await response.json().catch(() => ({ message: '删除失败' })); throw new Error(body.message) }
      } else {
        await api<AgentVersion>(`/api/agents/${agent.id}/versions/${version.id}/${action}`, { method: 'POST', body: '{}' })
      }
      if (selectedVersion === version.id && action !== 'restore') switchVersion('')
      await refresh()
      setNotice(action === 'archive' ? `v${version.versionNumber} 已归档，不再出现在对话选择中。` : action === 'restore' ? `v${version.versionNumber} 已恢复。` : `未被使用的 v${version.versionNumber} 已永久删除。`)
    })
  }

  async function sendMessage(event: FormEvent) {
    event.preventDefault()
    const input = chatInput.trim()
    if (!input) return
    setChatInput('')
    await runMessage(input)
  }

  async function runMessage(input: string, requestedTool?: { name: string; arguments: Record<string, unknown> }) {
    if (!input.trim() || !selectedVersion || busy) return
    setMessages((current) => [...current, { role: 'user', content: input.trim() }, { role: 'assistant', content: '' }]); setSources([]); setRunSteps([]); setPendingApproval(undefined); setCurrentRunId(undefined); setBusy(true); setNotice('')
    try {
      const response = await fetch('/api/chat/stream', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify({ agentVersionId: selectedVersion, conversationId, message: input.trim(), requestedTool }) })
      if (!response.ok || !response.body) {
        const body = await response.json().catch(() => ({ message: `HTTP ${response.status}` }))
        throw new Error(body.message ?? '无法建立流式连接')
      }
      await readSse(response.body, (eventName, data) => {
        if (eventName === 'run') { setConversationId(String(data.conversationId)); setCurrentRunId(String(data.runId)) }
        if (eventName === 'sources') setSources((data.items as RagSource[]) ?? [])
        if (eventName === 'evidence' && data.status === 'INSUFFICIENT') setNotice(String(data.message ?? '知识库证据不足'))
        if (eventName === 'step') setRunSteps((current) => [...current, { ...(data as RunStep), observedAt: Date.now() }])
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
        <button className={view === 'remote' ? 'active' : ''} onClick={() => setView('remote')}><span>06</span>远程工作台</button>
        <button className={view === 'runs' ? 'active' : ''} onClick={() => { setView('runs'); void refresh() }}><span>07</span>运行记录</button>
        <button className={view === 'system' ? 'active' : ''} onClick={() => { setView('system'); void inspectReadiness() }}><span>08</span>系统诊断</button>
      </nav>
      <div className={`connection connection--${backendState}`}><i />{backendState === 'online' ? '后端已连接' : backendState === 'checking' ? '正在连接' : '后端未连接'}</div>
    </aside>
    <main className="workspace">
      {notice && <div className={`notice ${view === 'remote' ? 'notice--remote' : ''}`}>{notice}<button onClick={() => setNotice('')}>×</button></div>}
      {view === 'models' && <section><PageHeader number="01" title="模型与凭据" description="连接参数保存在业务数据库；密钥由当前 Windows 用户的安全存储保护，也可由环境变量覆盖。" /><div className="two-column">
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
        <div className="two-column">
          <form className="panel form" onSubmit={submitSshWorkspace}><div className="section-head"><div><h2>SSH 远程工作区</h2><p className="hint">连接信息保存在本机数据库，密码使用 Windows 安全存储；当前仅开放 SFTP 只读工具。</p></div></div>
            <Field label="服务器公网 IP 或域名"><input required value={sshForm.host} onChange={(e) => { setSshForm({ ...sshForm, host: e.target.value }); setSshFingerprint(undefined) }} placeholder="例如：203.0.113.10" /></Field>
            <Field label="SSH 端口"><input required type="number" min="1" max="65535" value={sshForm.port} onChange={(e) => setSshForm({ ...sshForm, port: Number(e.target.value) })} /></Field>
            <Field label="SSH 用户名"><input required value={sshForm.username} onChange={(e) => setSshForm({ ...sshForm, username: e.target.value })} placeholder="云服务器登录页显示的用户，例如 ubuntu" /></Field>
            <Field label="远程项目目录"><input required value={sshForm.remoteRoot} onChange={(e) => setSshForm({ ...sshForm, remoteRoot: e.target.value })} placeholder="例如：/srv/my-website（不能填写 /）" /></Field>
            <Field label="服务器主机指纹"><div className="inline-field"><input required value={sshForm.hostKeySha256} onChange={(e) => setSshForm({ ...sshForm, hostKeySha256: e.target.value })} placeholder="点击右侧检测后，再与云服务器核对" /><button className="ghost" type="button" disabled={busy || !sshForm.host} onClick={() => void inspectSshFingerprint()}>检测指纹</button></div></Field>
            {sshFingerprint && <p className="edit-hint">协商算法：<code>{sshFingerprint.algorithm}</code><br />检测结果：<code>{sshFingerprint.sha256}</code><br />同一台服务器可能有多种主机密钥，请在服务器列出全部指纹，并核对同一种算法；不要拿 ED25519 指纹与 ECDSA/RSA 指纹比较。<br />{sshFingerprint.warning}</p>}
            <Field label={sshStatus?.passwordConfigured ? 'SSH 密码（留空则不更换）' : 'SSH 密码'}><input type="password" autoComplete="new-password" value={sshForm.password} onChange={(e) => setSshForm({ ...sshForm, password: e.target.value })} placeholder={sshStatus?.passwordConfigured ? '已安全保存' : '输入测试账户密码'} /></Field>
            <input type="hidden" value={sshForm.passwordSecret} readOnly />
            <div className="form-actions"><button className="primary" disabled={busy}>保存连接与密码</button><button className="secondary" type="button" disabled={busy || !sshStatus?.configured || !sshStatus.passwordConfigured} onClick={() => void testSshWorkspace()}>测试 SSH/SFTP 连接</button></div>
          </form>
          <div className="panel list-panel"><h2>这些值从哪里找？</h2>
            <article className="model-card"><div><strong>公网 IP、端口、用户名</strong><span>在云厂商实例的“远程登录/SSH 登录”页面查看。端口通常是 22，Linux 用户常见为 ubuntu、debian 或 root。</span></div></article>
            <article className="model-card"><div><strong>远程项目目录</strong><span>是网站代码在服务器中的绝对目录，例如 /srv/my-website 或 /var/www/example。它不是域名，也不能直接填 /。</span></div></article>
            <article className="model-card"><div><strong>主机指纹</strong><span>“检测指纹”只负责读取，仍需与云厂商控制台或服务器管理员提供的 SHA-256 指纹核对，防止连错服务器。</span></div></article>
            <div className="card-head"><div><strong>当前状态</strong><p>{sshStatus?.configured ? `${sshStatus.username}@${sshStatus.host}:${sshStatus.port}${sshStatus.remoteRoot}` : '尚未配置'}</p></div><span className={`badge badge--${sshStatus?.status === 'READY' ? 'ready' : 'warning'}`}>{sshStatus?.status ?? 'NOT_CONFIGURED'}</span></div>
            {sshStatus?.lastError && <p className="error-text">{sshStatus.lastError}</p>}
            <p className="hint">如果你暂时没有服务器资料，需要先打开云服务器控制台确认实例和登录方式；Agent Studio 无法凭空推断这些账户信息。</p>
          </div>
        </div>
        <div className="panel secret-panel"><div className="section-head"><div><h2>安全凭据 <small>{secrets.filter((item) => item.configured).length}/{secrets.length}</small></h2><p className="hint">密钥只会提交给本机后端并加密保存，页面和接口永不回显。环境变量的优先级更高。</p></div></div>
          {secrets.length === 0 ? <Empty text="创建模型或填写 MCP 凭据名称后，这里会出现对应项目" /> : <div className="secret-list">{secrets.map((secret) => <article key={secret.name} className="secret-card"><div><strong>{secret.name}</strong><span className={`badge badge--${secret.configured ? 'ready' : 'warning'}`}>{secret.configured ? secret.source === 'ENVIRONMENT' ? '环境变量' : '安全存储' : '未配置'}</span><p>{secret.usedBy.join(' · ')}</p></div><input type="password" autoComplete="new-password" value={secretValues[secret.name] ?? ''} onChange={(e) => setSecretValues((current) => ({ ...current, [secret.name]: e.target.value }))} placeholder={secret.configured ? '输入新值可替换' : '输入密钥'} /><div className="card-actions"><button className="secondary" disabled={busy || !(secretValues[secret.name]?.trim())} onClick={() => void saveSecret(secret.name)}>{secret.configured ? '更新' : '安全保存'}</button>{secret.configured && <button className="danger compact" disabled={busy || secret.source === 'ENVIRONMENT'} title={secret.source === 'ENVIRONMENT' ? '环境变量需在程序外清除' : undefined} onClick={() => void deleteSecret(secret.name)}>删除</button>}</div></article>)}</div>}
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
        <div className="panel list-panel"><h2>Agent 列表 <small>{agents.length}</small></h2>{agents.length === 0 ? <Empty text="先配置模型，再创建 Agent" /> : agents.map((agent) => {
          const agentVersions = versions.filter((version) => version.agentDefinitionId === agent.id)
          return <article className="agent-card" key={agent.id}><div className="card-head"><div><strong>{agent.name}</strong><p>{agent.description || '暂无简介'}</p></div><span className={`badge badge--${(agent.status ?? 'DRAFT').toLowerCase()}`}>{agent.status ?? 'DRAFT'}</span></div>{agent.draftToolNames.length > 0 && <div className="tool-chips">{agent.draftToolNames.map((name) => <span key={name}>{name}</span>)}</div>}<div className="agent-meta"><span>最新版本</span><b>{agent.latestVersionNumber ? `v${agent.latestVersionNumber}` : '未发布'}</b></div>{agentVersions.length > 0 && <details className="version-manager"><summary>管理历史版本（{agentVersions.length}）</summary>{agentVersions.map((version) => <div className={`version-row ${version.archived ? 'version-row--archived' : ''}`} key={version.id}><div><b>v{version.versionNumber}</b><span>{version.archived ? '已归档' : version.versionNumber === agent.latestVersionNumber ? '当前版本' : '历史版本'} · {version.usageCount} 条引用</span></div><div>{version.archived ? <><button className="ghost" disabled={busy} onClick={() => void changeVersionLifecycle(agent, version, 'restore')}>恢复</button>{version.deletable && <button className="danger compact" disabled={busy} onClick={() => void changeVersionLifecycle(agent, version, 'delete')}>永久删除</button>}</> : version.versionNumber !== agent.latestVersionNumber && <button className="ghost" disabled={busy} onClick={() => void changeVersionLifecycle(agent, version, 'archive')}>归档</button>}</div></div>)}</details>}<div className="card-actions"><button className="ghost" disabled={busy} onClick={() => editAgent(agent)}>编辑草稿</button><button className="secondary" disabled={busy} onClick={() => void publish(agent.id)}>发布新版本</button></div></article>
        })}</div>
      </div></section>}
      {view === 'chat' && <section><PageHeader number="05" title="对话测试台" description="选择已发布版本；MCP 工具与内置工具共享审批和运行记录。" />
        <div className="chat-toolbar"><label>Agent 版本<select value={selectedVersion} onChange={(e) => switchVersion(e.target.value)}><option value="">选择已发布版本</option>{versionLabels.map((version) => <option key={version.id} value={version.id}>{version.label}</option>)}</select></label><label className="history-toggle"><input type="checkbox" checked={showHistoricalVersions} onChange={(e) => setShowHistoricalVersions(e.target.checked)} />显示历史版本</label><span>{conversationId ? `会话 ${conversationId.slice(0, 8)}` : '新会话'}</span><button className="ghost" onClick={() => { setConversationId(undefined); setMessages([]); setSources([]); setRunSteps([]); setPendingApproval(undefined) }}>清空会话</button></div>
        <div className="chat-panel"><div className="messages">{pendingApproval && <aside className="approval-card" role="alert" aria-live="assertive"><div className="approval-heading"><strong>等待高风险操作审批</strong><b>{approvalSecondsLeft > 0 ? `${approvalSecondsLeft} 秒` : '已过期'}</b></div><p>工具：<code>{pendingApproval.toolName}</code> · {pendingApproval.capability}/{pendingApproval.riskLevel}</p><p>目标：{pendingApproval.targetEnvironment}</p><pre>{pendingApproval.argumentsJson}</pre><small>参数摘要：{pendingApproval.argumentsSha256.slice(0, 16)}… · {new Date(pendingApproval.expiresAt).toLocaleTimeString()} 前有效</small><div><button className="danger" disabled={approvalBusy || approvalSecondsLeft <= 0} onClick={() => void decideApproval(false)}>拒绝</button><button className="primary" disabled={approvalBusy || approvalSecondsLeft <= 0} onClick={() => void decideApproval(true)}>批准执行一次</button></div></aside>}{sources.length > 0 && <aside className="sources"><strong>本次检索来源</strong>{sources.map((source) => <details key={`${source.documentId}-${source.chunkIndex}`}><summary>{source.fileName} · chunk {source.chunkIndex} · {Math.round(source.score * 100)}%</summary><p>{source.content}</p></details>)}</aside>}{runSteps.length > 0 && <aside className="run-steps"><strong>运行步骤</strong>{runSteps.map((step) => <details key={step.id} open={step.stepType === 'TOOL_RESULT' || step.stepType.startsWith('APPROVAL')}><summary>#{step.stepNumber} {step.stepType}{step.toolName ? ` · ${step.toolName}` : ''}<span className={`step-status step-status--${step.status.toLowerCase()}`}>{step.status}</span></summary>{step.inputJson && <pre>输入：{step.inputJson}</pre>}{step.outputText && <pre>输出：{step.outputText}</pre>}{step.durationMs != null && <small>{step.durationMs} ms</small>}</details>)}</aside>}{messages.length === 0 ? <Empty text={versions.length ? '选择版本并发送第一条消息' : '请先发布一个 Agent 版本'} /> : messages.map((message, index) => <article className={`message message--${message.role}`} key={index}><span>{message.role === 'user' ? 'YOU' : 'AGENT'}</span>{message.content ? message.role === 'assistant' ? <MarkdownMessage content={message.content} /> : <p>{message.content}</p> : <p><i className="typing">正在生成</i></p>}</article>)}</div>
          <form className="composer" onSubmit={sendMessage}><textarea rows={3} value={chatInput} onChange={(e) => setChatInput(e.target.value)} placeholder="输入测试问题……" onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }} /><div className="composer-actions"><button className="primary" disabled={busy || !selectedVersion || !chatInput.trim()}>{busy ? '生成中' : '发送'}</button>{busy && currentRunId && <button className="danger" type="button" disabled={cancelBusy} onClick={() => void cancelCurrentRun()}>{cancelBusy ? '停止中' : '停止运行'}</button>}</div></form>
        </div>
      </section>}
      {view === 'remote' && <RemoteWorkbench sshStatus={sshStatus} deployment={deployment} deploymentForm={deploymentForm} onDeploymentFormChange={setDeploymentForm} onSaveDeployment={saveDeploymentProfile} onTestDeployment={testDeploymentProfile} versionLabels={versionLabels} selectedVersion={selectedVersion} onSelectVersion={switchVersion} messages={messages} steps={runSteps} runHistory={runHistory} pendingApproval={pendingApproval} approvalSecondsLeft={approvalSecondsLeft} approvalBusy={approvalBusy} busy={busy} currentRunId={currentRunId} cancelBusy={cancelBusy} onDecideApproval={decideApproval} onCancel={cancelCurrentRun} onRunMessage={runMessage} />}
      {view === 'runs' && <section><PageHeader number="07" title="运行记录" description="查看每次 AgentRun 的最终状态、耗时、错误和完整步骤。" />
        <div className="two-column run-history-layout"><div className="panel list-panel"><div className="section-head"><h2>最近运行 <small>{runHistory.length}</small></h2><button className="ghost" onClick={() => void refresh()}>刷新</button></div>{runHistory.length === 0 ? <Empty text="尚无运行记录" /> : runHistory.map((run) => <button className={`run-card ${selectedRun?.id === run.id ? 'active' : ''}`} key={run.id} onClick={() => void openRun(run.id)}><div><strong>{run.id.slice(0, 8)}</strong><span className={`badge badge--${run.status.toLowerCase()}`}>{run.status}</span></div><p>{new Date(run.startedAt).toLocaleString()} · {run.stepCount} 步</p>{run.errorMessage && <small>{run.errorMessage}</small>}</button>)}</div>
          <div className="panel run-detail">{!selectedRun ? <Empty text="选择一条运行查看完整步骤" /> : <><div className="card-head"><div><strong>运行 {selectedRun.id.slice(0, 8)}</strong><p>会话 {selectedRun.conversationId.slice(0, 8)}</p></div><span className={`badge badge--${selectedRun.status.toLowerCase()}`}>{selectedRun.status}</span></div><dl><div><dt>AgentVersion</dt><dd>{selectedRun.agentVersionId}</dd></div><div><dt>开始</dt><dd>{new Date(selectedRun.startedAt).toLocaleString()}</dd></div>{selectedRun.completedAt && <div><dt>结束</dt><dd>{new Date(selectedRun.completedAt).toLocaleString()}</dd></div>}</dl>{selectedRun.errorMessage && <p className="run-error">{selectedRun.errorMessage}</p>}<aside className="run-steps"><strong>完整步骤</strong>{selectedRun.steps.map((step) => <details key={step.id}><summary>#{step.stepNumber} {step.stepType}{step.toolName ? ` · ${step.toolName}` : ''}<span className={`step-status step-status--${step.status.toLowerCase()}`}>{step.status}</span></summary>{step.inputJson && <pre>输入：{step.inputJson}</pre>}{step.outputText && <pre>输出：{step.outputText}</pre>}{step.durationMs != null && <small>{step.durationMs} ms</small>}</details>)}</aside><aside className="audit-events"><strong>安全审计</strong>{selectedAuditEvents.length === 0 ? <p>该运行没有工具审计事件</p> : selectedAuditEvents.map((event) => <article key={event.id}><div><b>{event.eventType}</b><span className={`step-status step-status--${event.status.toLowerCase()}`}>{event.status}</span></div><small>{new Date(event.createdAt).toLocaleTimeString()} · {event.toolName} · {event.capability}/{event.riskLevel}</small>{event.argumentsSha256 && <code>参数摘要 {event.argumentsSha256.slice(0, 16)}…</code>}{event.details && <p>{event.details}</p>}</article>)}</aside></>}</div></div>
      </section>}
      {view === 'system' && <section><PageHeader number="08" title="系统诊断" description="正式使用前逐项确认数据库、向量模型、密钥、数据目录和 MCP 是否真正可用。" />
        <div className="panel readiness-panel">
          <div className="section-head"><div><h2>版本 {readiness?.version ?? '读取中'}</h2>{readiness && <p className="hint">上次检查 {new Date(readiness.timestamp).toLocaleString()}</p>}</div><div className="readiness-summary"><span className={`badge badge--${(readiness?.status ?? 'checking').toLowerCase()}`}>{readiness?.status ?? 'CHECKING'}</span><button className="secondary" disabled={readinessBusy} onClick={() => void inspectReadiness()}>{readinessBusy ? '检查中…' : '重新检查'}</button></div></div>
          {!readiness ? <Empty text="正在检查本机运行环境" /> : <div className="readiness-list">{readiness.checks.map((check) => <article key={check.id} className={`readiness-card readiness-card--${check.status.toLowerCase()}`}><div><strong>{check.name}</strong><span className={`badge badge--${check.status.toLowerCase()}`}>{check.status}</span></div><p>{check.detail}</p>{check.action && <small>建议：{check.action}</small>}</article>)}</div>}
        </div>
      </section>}
    </main>
  </div>
}

type RemoteWorkbenchProps = {
  sshStatus?: SshWorkspaceStatus
  deployment?: DeploymentProfile
  deploymentForm: typeof emptyDeployment
  onDeploymentFormChange: (value: typeof emptyDeployment) => void
  onSaveDeployment: (value: typeof emptyDeployment) => Promise<void>
  onTestDeployment: () => Promise<void>
  versionLabels: (AgentVersion & { label: string })[]
  selectedVersion: string
  onSelectVersion: (id: string) => void
  messages: ChatMessage[]
  steps: RunStep[]
  runHistory: RunSummary[]
  pendingApproval?: ApprovalRequest
  approvalSecondsLeft: number
  approvalBusy: boolean
  busy: boolean
  currentRunId?: string
  cancelBusy: boolean
  onDecideApproval: (approved: boolean) => Promise<void>
  onCancel: () => Promise<void>
  onRunMessage: (message: string, requestedTool?: { name: string; arguments: Record<string, unknown> }) => Promise<void>
}

type WorkbenchTab = 'overview' | 'files' | 'changes' | 'tasks' | 'deployment' | 'output'
type RemoteEntry = { name: string; path: string; type: string; sizeBytes?: number }
type DirectoryResult = { target: string; path: string; entries: RemoteEntry[]; truncated: boolean }
type FileResult = { path: string; sha256: string; sizeBytes: number; startLine: number; endLine: number; totalLines: number; content: string; truncated: boolean }
type TaskResult = { task: string; target: string; path?: string; deploymentRoot?: string; composeProject?: string; successful: boolean; exitCode: number; durationMs: number; output: string; outputTruncated: boolean; schemaComplete?: boolean; schemaSha256?: string; observedAt?: number }
type BackupResult = { target: string; deploymentRoot: string; backupRoot: string; backupId?: string; backupPath?: string; successful: boolean; exitCode: number; durationMs: number; databaseBytes: number; uploadsBytes: number; fileCount: number; manifestSha256?: string; output: string; outputTruncated: boolean }
type RestoreDrillResult = { target: string; backupRoot: string; backupId?: string; backupPath?: string; drillId?: string; drillPath?: string; successful: boolean; exitCode: number; durationMs: number; databaseBytes: number; restoredUploadsBytes: number; restoredFileCount: number; manifestSha256?: string; drillSha256?: string; productionModified: boolean; databaseImported: boolean; output: string; outputTruncated: boolean }
type ReleaseCandidateResult = { releaseId?: string; target: string; localSourceRoot: string; artifactPath: string; remoteReleaseRoot: string; candidatePath?: string; stage: string; successful: boolean; exitCode: number; durationMs: number; artifactBytes: number; artifactSha256?: string; manifestSha256?: string; output: string; outputTruncated: boolean; productionModified: boolean; imageBuilt: boolean }
type ReleaseImageResult = { releaseId: string; manifestSha256: string; candidatePath: string; imageTag: string; imageId?: string; buildAttemptPath: string; target: string; stage: string; successful: boolean; exitCode: number; durationMs: number; output: string; outputTruncated: boolean }
type BaselineResult = { successful: boolean; exitCode: number; baselineRegistered?: boolean; target: string; output?: string; observedAt?: number }

const remoteTasks = [
  { id: 'GIT_STATUS', title: 'Git 状态', detail: '查看分支和工作区状态', tone: 'READ' },
  { id: 'GIT_DIFF_SUMMARY', title: '变更摘要', detail: '查看文件级增删统计', tone: 'READ' },
  { id: 'MAVEN_TEST', title: 'Maven 测试', detail: '运行固定 mvn test', tone: 'TEST' },
  { id: 'NPM_TEST', title: 'npm 测试', detail: '运行 package.json 的 test script', tone: 'TEST' },
  { id: 'NPM_BUILD', title: 'npm 构建', detail: '运行 package.json 的 build script', tone: 'BUILD' },
] as const

const deploymentTasks = [
  { id: 'COMPOSE_VALIDATE', title: 'Compose 校验', detail: '只做固定 Compose 配置语法检查' },
  { id: 'COMPOSE_STATUS', title: '服务状态', detail: '读取 nginx、app、mysql、phpmyadmin 状态' },
  { id: 'NGINX_VALIDATE', title: 'Nginx 校验', detail: '在固定 nginx 容器内执行 nginx -t' },
  { id: 'SITE_HEALTH', title: '站点健康', detail: '访问服务器回环地址上的固定健康路径' },
  { id: 'RELEASE_FINGERPRINT', title: '发布指纹', detail: '读取固定制品的摘要、大小和修改时间' },
  { id: 'DATABASE_SCHEMA', title: '数据库结构核查', detail: '只读表、字段、索引与外键；不读业务数据、不登记基线' },
  { id: 'DATABASE_BASELINE_STATUS', title: '数据库基线状态', detail: '只读版本历史的版本、类型与成功状态；登记异常时先检查，不盲目重试' },
  { id: 'RELEASE_STATUS', title: '当前线上版本', detail: '读取 app 镜像、容器健康与生产文件指纹；不执行切换' },
] as const

function RemoteWorkbench(props: RemoteWorkbenchProps) {
  const [tab, setTab] = useState<WorkbenchTab>('overview')
  const [directoryPath, setDirectoryPath] = useState('.')
  const [filePath, setFilePath] = useState('')
  const [taskPath, setTaskPath] = useState('.')
  const [assistantInput, setAssistantInput] = useState('')
  const [directoryBusy, setDirectoryBusy] = useState(false)
  const [fileBusy, setFileBusy] = useState(false)
  const [browserError, setBrowserError] = useState('')
  const [imageReleaseId, setImageReleaseId] = useState('')
  const [imageManifestSha, setImageManifestSha] = useState('')
  const [baselineInput, setBaselineInput] = useState('{}')
  const [historySteps, setHistorySteps] = useState<RunStep[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState('')
  const [reviewedPriorFailure, setReviewedPriorFailure] = useState(false)
  const [workflowNow, setWorkflowNow] = useState(Date.now())
  const remoteVersions = props.versionLabels.filter((version) => version.toolNames.some((name) => name.includes('remote_workspace') || name === 'inspect_remote_deployment' || name === 'prepare_remote_deployment_backup' || name === 'verify_remote_deployment_backup_restore' || name === 'prepare_release_candidate' || name === 'build_release_candidate_image' || name === 'adopt_remote_database_baseline'))
  const evidenceSteps = useMemo(() => [...historySteps, ...props.steps], [historySteps, props.steps])
  const parsedTask = useMemo(() => latestToolJson<TaskResult>(props.steps, 'run_remote_workspace_task'), [props.steps])
  const deploymentResult = useMemo(() => latestToolJson<TaskResult>(evidenceSteps, 'inspect_remote_deployment'), [evidenceSteps])
  const schemaResult = useMemo(() => latestDeploymentTask(evidenceSteps, 'DATABASE_SCHEMA'), [evidenceSteps])
  const baselineStatusResult = useMemo(() => latestDeploymentTask(evidenceSteps, 'DATABASE_BASELINE_STATUS'), [evidenceSteps])
  const siteHealthResult = useMemo(() => latestDeploymentTask(evidenceSteps, 'SITE_HEALTH'), [evidenceSteps])
  const restoreDrillResult = useMemo(() => latestToolJson<RestoreDrillResult>(evidenceSteps, 'verify_remote_deployment_backup_restore'), [evidenceSteps])
  const backupResult = useMemo(() => latestToolJson<BackupResult>(evidenceSteps, 'prepare_remote_deployment_backup'), [evidenceSteps])
  const releaseCandidateResult = useMemo(() => latestToolJson<ReleaseCandidateResult>(evidenceSteps, 'prepare_release_candidate'), [evidenceSteps])
  const releaseImageResult = useMemo(() => latestToolJson<ReleaseImageResult>(evidenceSteps, 'build_release_candidate_image'), [evidenceSteps])
  const baselineResult = useMemo(() => latestToolJson<BaselineResult>(evidenceSteps, 'adopt_remote_database_baseline'), [evidenceSteps])
  const productionStatus = useMemo(() => latestDeploymentTask(evidenceSteps, 'RELEASE_STATUS') as (TaskResult & ProductionStatus) | undefined, [evidenceSteps])
  const publishResult = useMemo(() => latestToolJson<{ task: string; successful: boolean; exitCode: number; target: string; deployed: boolean; rolledBack: boolean; manualInterventionRequired: boolean; currentImageId?: string; observedAt?: number }>(evidenceSteps, 'publish_remote_release'), [evidenceSteps])
  const [publishAcknowledged, setPublishAcknowledged] = useState(false)
  const [directory, setDirectory] = useState<DirectoryResult>()
  const [file, setFile] = useState<FileResult>()
  const [task, setTask] = useState<TaskResult>()
  const selectedReady = remoteVersions.some((version) => version.id === props.selectedVersion)
  const restoreDrillReady = remoteVersions.find((version) => version.id === props.selectedVersion)?.toolNames.includes('verify_remote_deployment_backup_restore') ?? false
  const releaseCandidateReady = remoteVersions.find((version) => version.id === props.selectedVersion)?.toolNames.includes('prepare_release_candidate') ?? false
  const releaseImageReady = remoteVersions.find((version) => version.id === props.selectedVersion)?.toolNames.includes('build_release_candidate_image') ?? false
  const baselineReady = remoteVersions.find((version) => version.id === props.selectedVersion)?.toolNames.includes('adopt_remote_database_baseline') ?? false
  const publishToolReady = props.versionLabels.find((version) => version.id === props.selectedVersion)?.toolNames.includes('publish_remote_release') ?? false
  const browserReady = props.sshStatus?.status === 'READY' && props.sshStatus.passwordConfigured
  const browserTarget = `${props.sshStatus?.username ?? ''}@${props.sshStatus?.host ?? ''}:${props.sshStatus?.port ?? ''}${props.sshStatus?.remoteRoot ?? ''}#${props.sshStatus?.hostKeySha256 ?? ''}`
  const visibleMessages = props.messages.slice(-6)
  const hiddenMessageCount = Math.max(0, props.messages.length - visibleMessages.length)
  const expectedTarget = `${props.sshStatus?.username ?? ''}@${props.sshStatus?.host ?? ''}:${props.sshStatus?.port ?? ''}${props.deployment?.remoteDeployRoot ?? ''}`
  const workflow = workflowReadiness({ candidate: releaseCandidateResult, image: releaseImageResult, backup: backupResult,
    schema: schemaResult, baseline: baselineResult, status: baselineStatusResult, health: siteHealthResult }, expectedTarget, workflowNow)
  const baselineEvidence = { releaseId: releaseImageResult?.releaseId, manifestSha256: releaseImageResult?.manifestSha256,
    imageId: releaseImageResult?.imageId, schemaSha256: schemaResult?.schemaSha256,
    backupId: backupResult?.backupId, backupManifestSha256: backupResult?.manifestSha256 }
  const publishState = publishReadiness({ candidate: releaseCandidateResult, image: releaseImageResult, backup: backupResult,
    schema: schemaResult, status: baselineStatusResult }, productionStatus, expectedTarget, workflowNow,
    publishResult?.target === expectedTarget ? publishResult.observedAt : 0)
  let baselineInputMatches = false
  try {
    const parsed = JSON.parse(baselineInput) as Record<string, unknown>
    baselineInputMatches = Object.entries(baselineEvidence).every(([key, value]) => typeof value === 'string' && parsed[key] === value)
      && Object.keys(parsed).length === 6
  } catch { /* incomplete evidence remains disabled */ }

  useEffect(() => {
    if (tab !== 'deployment') return
    const timer = window.setInterval(() => setWorkflowNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [tab])

  useEffect(() => {
    if (tab !== 'deployment') return
    let cancelled = false
    setHistoryLoading(true); setHistoryError('')
    const relevant = new Set(['prepare_release_candidate', 'build_release_candidate_image', 'prepare_remote_deployment_backup',
      'inspect_remote_deployment', 'adopt_remote_database_baseline', 'verify_remote_deployment_backup_restore', 'publish_remote_release'])
    Promise.all(props.runHistory.slice(0, 50).map((run) => api<AgentRun>(`/api/runs/${run.id}`).catch(() => undefined)))
      .then((runs) => {
        if (!cancelled) setHistorySteps(runs.reverse().flatMap((run) => run?.steps.filter((step) => step.stepType === 'TOOL_RESULT'
          && step.toolName && relevant.has(step.toolName)).map((step) => ({ ...step, observedAt: Date.parse(run.startedAt) })) ?? []))
      })
      .catch(() => { if (!cancelled) setHistoryError('无法读取历史运行；历史步骤暂不计入流程') })
      .finally(() => { if (!cancelled) setHistoryLoading(false) })
    return () => { cancelled = true }
  }, [tab, props.runHistory])

  useEffect(() => { if (parsedTask) setTask(parsedTask) }, [parsedTask])
  useEffect(() => { setBaselineInput('{}') }, [props.selectedVersion, browserTarget, props.deployment?.remoteDeployRoot, props.deployment?.remoteBackupRoot, props.deployment?.composeProject])
  useEffect(() => {
    const evidence: Record<string, string> = {}
    if (releaseImageResult?.successful && releaseImageResult.imageId) Object.assign(evidence, { releaseId: releaseImageResult.releaseId, manifestSha256: releaseImageResult.manifestSha256, imageId: releaseImageResult.imageId })
    if (schemaResult?.schemaComplete && schemaResult.schemaSha256) evidence.schemaSha256 = schemaResult.schemaSha256
    if (backupResult?.successful && backupResult.backupId && backupResult.manifestSha256) Object.assign(evidence, { backupId: backupResult.backupId, backupManifestSha256: backupResult.manifestSha256 })
    if (Object.keys(evidence).length) setBaselineInput(current => mergeBaselineEvidence(current, evidence))
  }, [releaseImageResult, schemaResult, backupResult])
  useEffect(() => {
    if (releaseCandidateResult?.successful && releaseCandidateResult.releaseId && releaseCandidateResult.manifestSha256) {
      setImageReleaseId(releaseCandidateResult.releaseId); setImageManifestSha(releaseCandidateResult.manifestSha256)
    }
  }, [releaseCandidateResult])
  useEffect(() => {
    setDirectory(undefined); setFile(undefined); setDirectoryPath('.'); setFilePath('')
    if (!browserReady) return
    setDirectoryBusy(true); setBrowserError('')
    api<DirectoryResult>('/api/ssh/workspace/browser/directory?path=.&maxEntries=100')
      .then((result) => { setDirectory(result); setDirectoryPath(result.path || '.') })
      .catch((error) => setBrowserError(error instanceof Error ? error.message : '读取远程目录失败'))
      .finally(() => setDirectoryBusy(false))
  }, [browserReady, browserTarget])

  function requireRelativePath(value: string, label: string) {
    const normalized = value.trim().replace(/\\/g, '/')
    if (!normalized) throw new Error(`${label}不能为空`)
    if (normalized.startsWith('/') || /^[A-Za-z]:/.test(normalized) || normalized.split('/').includes('..')) {
      throw new Error(`${label}必须是授权根内的安全相对路径`)
    }
    return normalized
  }

  async function requestDirectory(path = directoryPath) {
    try {
      const safePath = requireRelativePath(path, '目录')
      setDirectoryPath(safePath); setDirectoryBusy(true); setBrowserError('')
      const result = await api<DirectoryResult>(`/api/ssh/workspace/browser/directory?path=${encodeURIComponent(safePath)}&maxEntries=100`)
      setDirectory(result); setDirectoryPath(result.path || '.')
    } catch (error) { setBrowserError(error instanceof Error ? error.message : '目录无效') }
    finally { setDirectoryBusy(false) }
  }

  function parentDirectory() {
    const current = (directory?.path || directoryPath || '.').replace(/\\/g, '/').replace(/^\.\//, '').replace(/\/+$/, '')
    if (!current || current === '.') return '.'
    const segments = current.split('/').filter(Boolean)
    segments.pop()
    return segments.length ? segments.join('/') : '.'
  }

  async function requestFile(path = filePath) {
    try {
      const safePath = requireRelativePath(path, '文件')
      setFilePath(safePath); setTab('files'); setFileBusy(true); setBrowserError('')
      const result = await api<FileResult>(`/api/ssh/workspace/browser/file?path=${encodeURIComponent(safePath)}&startLine=1&maxLines=200`)
      setFile(result)
    } catch (error) { setBrowserError(error instanceof Error ? error.message : '文件无效') }
    finally { setFileBusy(false) }
  }

  async function requestTask(taskId: string) {
    try {
      const safePath = requireRelativePath(taskPath, '项目目录')
      setTab('output')
      await props.onRunMessage(`请只使用 run_remote_workspace_task 工具，在相对项目目录 ${JSON.stringify(safePath)} 运行固定任务 ${taskId}。不得提供命令、参数、环境变量或 Shell 文本。`, { name: 'run_remote_workspace_task', arguments: { task: taskId, path: safePath } })
    } catch (error) { window.alert(error instanceof Error ? error.message : '项目目录无效') }
  }

  async function requestDeploymentTask(taskId: string) {
    setTab('deployment')
    await props.onRunMessage(`请只使用 inspect_remote_deployment 工具运行固定只读部署诊断 ${taskId}。不得提供路径、命令、服务名、URL、参数、环境变量或 Shell 文本。`, { name: 'inspect_remote_deployment', arguments: { task: taskId } })
  }

  async function requestPublish() {
    if (!publishState.ready || !publishAcknowledged || !publishToolReady) return
    setPublishAcknowledged(false)
    await props.onRunMessage(`请只使用 publish_remote_release 工具，按下列八项绑定身份执行一次受审上线、健康验证与失败恢复。不得添加SQL、路径、服务、命令或选项。${JSON.stringify(publishState.args)}`,
      { name: 'publish_remote_release', arguments: publishState.args })
  }

  async function requestDeploymentBackup() {
    setTab('deployment')
    await props.onRunMessage('请只使用 prepare_remote_deployment_backup 工具创建一次固定发布前备份。不得提供路径、名称、命令、参数、环境变量、覆盖、删除或恢复选项。', { name: 'prepare_remote_deployment_backup', arguments: {} })
  }

  async function requestRestoreDrill() {
    setTab('deployment')
    await props.onRunMessage('请只使用 verify_remote_deployment_backup_restore 工具，对固定备份根内最新的合格备份执行一次隔离恢复材料演练。不得提供备份 ID、路径、命令、参数、环境变量、生产恢复、覆盖或删除选项。', { name: 'verify_remote_deployment_backup_restore', arguments: {} })
  }

  async function requestReleaseCandidate() {
    setTab('deployment')
    await props.onRunMessage('请只使用 prepare_release_candidate 工具，从固定本地源码执行测试、打包并暂存一个不可变发布候选。不得提供路径、制品、命令、参数、环境变量、版本号、覆盖或部署选项。', { name: 'prepare_release_candidate', arguments: {} })
  }

  async function requestReleaseImage() {
    try {
      const message = releaseImageRequest(imageReleaseId.trim(), imageManifestSha.trim())
      setTab('deployment')
      await props.onRunMessage(message, { name: 'build_release_candidate_image', arguments: { releaseId: imageReleaseId.trim(), manifestSha256: imageManifestSha.trim() } })
    } catch (error) { window.alert(error instanceof Error ? error.message : '候选身份无效') }
  }

  async function requestBaseline() {
    try {
      const fresh = workflowReadiness({ candidate: releaseCandidateResult, image: releaseImageResult, backup: backupResult,
        schema: schemaResult, baseline: baselineResult, status: baselineStatusResult, health: siteHealthResult }, expectedTarget, Date.now())
      if (!fresh.canRegister || !baselineInputMatches || (baselineResult && !baselineResult.successful && !reviewedPriorFailure))
        throw new Error('登记前必须核对同一目标的新镜像、30分钟内备份、完整结构摘要和六项绑定身份；前次失败还需人工确认。')
      await props.onRunMessage(databaseBaselineRequest(baselineInput), { name: 'adopt_remote_database_baseline', arguments: JSON.parse(baselineInput) })
    }
    catch (error) { window.alert(error instanceof Error ? error.message : '绑定身份无效') }
  }

  async function submitDeployment(event: FormEvent) {
    event.preventDefault(); await props.onSaveDeployment(props.deploymentForm)
  }

  async function submitAssistant(event: FormEvent) {
    event.preventDefault()
    const value = assistantInput.trim()
    if (!value) return
    setAssistantInput('')
    await props.onRunMessage(value)
  }

  return <section className="remote-workbench-section">
    <header className="remote-workbench-header">
      <div><p className="eyebrow">CONTROLLED REMOTE WORKSPACE</p><h2>远程工作台</h2><p>熟悉的 SSH 编码与运维体验，执行权限仍由固定工具、一次性审批和审计边界控制。</p></div>
      <div className="remote-target-summary"><span className={`connection-dot connection-dot--${props.sshStatus?.status === 'READY' ? 'ready' : 'warning'}`} /><div><strong>{props.sshStatus?.configured ? `${props.sshStatus.username}@${props.sshStatus.host}:${props.sshStatus.port}` : 'SSH 尚未配置'}</strong><small>{props.sshStatus?.remoteRoot || '请先在模型配置页设置远程根目录'}</small></div></div>
    </header>
    <div className="remote-toolbar">
      <label>执行 Agent<select value={props.selectedVersion} onChange={(event) => props.onSelectVersion(event.target.value)}><option value="">选择包含远程工具的已发布版本</option>{remoteVersions.map((version) => <option key={version.id} value={version.id}>{version.label}</option>)}</select></label>
      <span className={`badge badge--${props.sshStatus?.status === 'READY' ? 'ready' : 'warning'}`}>{props.sshStatus?.status ?? 'NOT_CONFIGURED'}</span>
      <span className="remote-policy">SSH · 固定指纹 · 受限根目录 · HIGH 审批</span>
    </div>
    <div className="remote-workbench">
      <aside className="remote-explorer">
        <div className="remote-pane-title"><div><span>EXPLORER</span><strong>远程文件</strong></div><button disabled={!browserReady || directoryBusy} onClick={() => void requestDirectory()}>{directoryBusy ? '…' : '↻'}</button></div>
        <div className="remote-path-input"><button className="remote-parent-button" disabled={!browserReady || directoryBusy || (directory?.path || directoryPath) === '.'} onClick={() => void requestDirectory(parentDirectory())} title="返回父目录">↑ 上级</button><input value={directoryPath} onChange={(event) => setDirectoryPath(event.target.value)} aria-label="远程相对目录" /><button disabled={!browserReady || directoryBusy} onClick={() => void requestDirectory()}>打开</button></div>
        <div className="remote-tree">
          {browserError && <p className="error-text">{browserError}</p>}
          {!directory ? <p>{directoryBusy ? '正在通过受限 SFTP 读取目录…' : browserReady ? '点击刷新读取授权根目录。' : '先完成 SSH/SFTP 连接测试。这里不会显示 `.env`、密钥和受保护路径。'}</p> : <><small>{directory.path || '.'}{directory.truncated ? ' · 已截断' : ''}</small>{directory.entries.map((entry) => <button key={entry.path} disabled={directoryBusy || fileBusy} onClick={() => { if (entry.type === 'DIRECTORY') void requestDirectory(entry.path); else { setFilePath(entry.path); void requestFile(entry.path) } }}><i>{entry.type === 'DIRECTORY' ? '▸' : entry.type === 'FILE' ? '·' : '×'}</i><span>{entry.name}</span>{entry.type === 'FILE' && <em>{formatCompactBytes(entry.sizeBytes ?? 0)}</em>}</button>)}</>}
        </div>
        <div className="remote-protection"><strong>受保护边界</strong><p>禁止越界、符号链接、隐藏凭据与任意 Shell。</p></div>
      </aside>

      <main className="remote-center">
        <div className="remote-tabs">{([['overview', '概览'], ['files', '文件'], ['changes', '变更'], ['tasks', '任务'], ['deployment', '部署'], ['output', '输出']] as [WorkbenchTab, string][]).map(([id, label]) => <button className={tab === id ? 'active' : ''} key={id} onClick={() => setTab(id)}>{label}</button>)}</div>
        <div className="remote-tab-content">
          {tab === 'overview' && <div className="remote-overview"><div className="remote-hero-card"><span>REMOTE ROOT</span><strong>{props.sshStatus?.remoteRoot || '尚未配置'}</strong><p>用户浏览目录和只读文本直接使用受限 SFTP；Agent 的写入与执行仍必须经过固定工具、一次性审批和审计。</p></div><div className="remote-metrics"><article><b>0</b><span>浏览所需模型调用</span></article><article><b>1×</b><span>写入/执行审批</span></article><article><b>16K</b><span>最大任务输出</span></article></div><div className="remote-flow"><strong>安全边界</strong><p>人工只读浏览 → 固定指纹 + RemotePathPolicy；Agent 写入/执行 → ToolRegistry + SafeExecutionGateway + ApprovalRequest + AuditEvent</p></div></div>}
          {tab === 'files' && <div className="remote-file-view"><div className="remote-file-toolbar"><input value={filePath} onChange={(event) => setFilePath(event.target.value)} placeholder="输入授权根内的相对文件路径" /><button className="secondary" disabled={!browserReady || fileBusy || !filePath.trim()} onClick={() => void requestFile()}>{fileBusy ? '读取中…' : '只读打开'}</button></div>{browserError && <p className="error-text">{browserError}</p>}{!file ? <Empty text="从左侧文件树选择文本文件，或输入相对路径" /> : <><div className="remote-file-meta"><strong>{file.path}</strong><span>{file.startLine}–{file.endLine} / {file.totalLines} 行 · {formatCompactBytes(file.sizeBytes)}{file.truncated ? ' · 已截断' : ''}</span><code>SHA-256 {file.sha256}</code></div><pre className="remote-code">{file.content}</pre></>}</div>}
          {tab === 'changes' && <div className="remote-placeholder"><span>DIFF / ARTIFACT</span><h3>受审变更区</h3><p>远程补丁仍由 Agent 生成精确替换，并在审批卡中绑定目标、参数和文件摘要。下一批会在此提供并排 Diff 与恢复建议。</p><button className="secondary" onClick={() => setTab('output')}>查看当前运行步骤</button></div>}
          {tab === 'tasks' && <div className="remote-tasks"><div className="remote-task-path"><label>相对项目目录<input value={taskPath} onChange={(event) => setTaskPath(event.target.value)} /></label><small>任务命令由平台固定映射，输入框只接受授权根内的相对目录。</small></div><div className="remote-task-grid">{remoteTasks.map((item) => <article key={item.id}><div><span>{item.tone}</span><b>{item.title}</b></div><p>{item.detail}</p><code>fixed:{item.id}</code><button className="primary" disabled={!selectedReady || props.busy} onClick={() => void requestTask(item.id)}>请求执行</button></article>)}</div></div>}
          {tab === 'deployment' && <div className="remote-deployment">
            <div className="remote-deployment-head"><div><span>GUIDED CONTROLLED RELEASE</span><h3>准备 → 受审上线 → 只读验收</h3><p>前六步准备候选和首次数据库基线；下面的第七步才会切换网站。已登记基线的数据库不要重复登记。每次上线仍须新备份和一次性审批。</p></div><span className={`badge badge--${props.deployment?.status === 'READY' ? 'ready' : 'warning'}`}>{props.deployment?.status ?? 'NOT_CONFIGURED'}</span></div>
            <section className="release-guide" aria-label="六步操作引导">
              <div className="release-guide-intro"><strong>先看状态，再点当前步骤</strong><p>绿色表示工具结果已核对；灰色表示尚无足够证据。会话完成不等于工具成功。刷新后会从运行记录恢复最近的结果。</p>{historyLoading && <small>正在恢复历史结果…</small>}{historyError && <small className="error-text">{historyError}</small>}</div>
              <ol className="release-guide-steps">
                <li className={workflow.candidate ? 'done' : 'next'}><header><span>01</span><div><h4>准备候选</h4><p>本地测试并打包，把固定文件放进服务器的新候选目录；不影响网站。</p></div><b>{workflow.candidate ? '已完成' : '待执行'}</b></header><p className="release-guide-proof">成功证据：候选 ID 与 64 位清单摘要。失败可能留下构建文件或未完成目录，不能用于下一步。</p>{releaseCandidateResult && <small>最近候选：{releaseCandidateResult.releaseId || releaseCandidateResult.stage} · {releaseCandidateResult.successful ? '成功' : '失败'}</small>}<button className="primary" disabled={!releaseCandidateReady || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestReleaseCandidate()}>{workflow.candidate ? '重新准备候选' : '准备候选'}</button></li>
                <li className={workflow.image ? 'done' : workflow.candidate ? 'next' : 'blocked'}><header><span>02</span><div><h4>构建并自检镜像</h4><p>生成独立镜像，以非 root、无网络方式验证启动；不切换生产。</p></div><b>{workflow.image ? '已完成' : workflow.candidate ? '可执行' : '等待候选'}</b></header><p className="release-guide-proof">成功证据：IMAGE_READY、新 imageId 与 RUNTIME_SMOKE。失败可能留下镜像或构建记录，占用磁盘。</p>{releaseImageResult && <small>最近镜像：{releaseImageResult.imageId || releaseImageResult.stage} · {releaseImageResult.successful ? '成功' : '失败'}</small>}<button className="primary" disabled={!releaseImageReady || !workflow.candidate || !imageReleaseId || !imageManifestSha || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestReleaseImage()}>构建镜像</button></li>
                <li className={workflow.backup ? 'done' : workflow.image ? 'next' : 'blocked'}><header><span>03</span><div><h4>创建近期备份</h4><p>新建数据库与 uploads 等材料的备份；不覆盖旧备份、不恢复数据库。</p></div><b>{workflow.backup ? '30分钟内有效' : backupResult?.successful ? '已过期或不匹配' : '待执行'}</b></header><p className="release-guide-proof">成功证据：备份 ID、清单摘要。失败可能留有不完整目录；备份过期不会自动删除，只是不能用于登记。</p>{backupResult && <small>最近备份：{backupResult.backupId || '失败'}{workflow.backupAgeMinutes !== undefined ? ` · 已过 ${Math.max(0, Math.floor(workflow.backupAgeMinutes))} 分钟` : ''}</small>}<button className="primary" disabled={!selectedReady || !workflow.image || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestDeploymentBackup()}>{workflow.backup ? '重新创建备份' : '创建备份'}</button></li>
                <li className={workflow.schema ? 'done' : workflow.image ? 'next' : 'blocked'}><header><span>04</span><div><h4>核对数据库结构</h4><p>只读采集表、字段、索引和外键，不读取业务数据，不登记版本。</p></div><b>{workflow.schema ? '已完成' : '待执行'}</b></header><p className="release-guide-proof">成功证据：结构完整及 schemaSha256。失败不应改变数据库，但不能继续登记。</p>{schemaResult && <small>最近结构核查：{schemaResult.successful && schemaResult.schemaComplete ? schemaResult.schemaSha256 : `失败，退出码 ${schemaResult.exitCode}`}</small>}<button className="primary" disabled={!selectedReady || !workflow.image || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestDeploymentTask('DATABASE_SCHEMA')}>只读核查结构</button></li>
                <li className={workflow.baseline ? 'done' : workflow.canRegister ? 'next' : 'blocked'}><header><span>05</span><div><h4>登记版本 1 基线</h4><p>首次在生产数据库建立 Flyway 历史；短暂阻止业务写入，是本流程唯一会改数据库的一步。</p></div><b>{workflow.baseline ? '已登记' : workflow.canRegister ? '待审批' : '证据未齐'}</b></header><p className="release-guide-proof">必须绑定同一目标的候选、镜像、结构和近期备份。失败后可能已有部分历史，先查状态，不盲重试。</p><details className="release-identity"><summary>查看本次六项绑定证据</summary><pre>{baselineInput}</pre></details>{baselineResult && !baselineResult.successful && <label className="release-guide-warning"><input type="checkbox" checked={reviewedPriorFailure} onChange={(event) => setReviewedPriorFailure(event.target.checked)} />我已复核上次失败及只读基线状态，知晓不能把失败当成无副作用</label>}{!baselineInputMatches && <small>六项身份尚未齐全或与当前结果不一致；不能登记。</small>}<button className="danger" disabled={!baselineReady || !workflow.canRegister || !baselineInputMatches || (Boolean(baselineResult) && !baselineResult?.successful && !reviewedPriorFailure) || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestBaseline()}>请求登记基线</button></li>
                <li className={workflow.baseline && workflow.status && workflow.health ? 'done' : workflow.baseline ? 'next' : 'blocked'}><header><span>06</span><div><h4>只读验收</h4><p>确认 Flyway 版本 1 记录，再核查网站健康；不做发布切换。</p></div><b>{workflow.baseline && workflow.status && workflow.health ? '已完成' : '等待登记'}</b></header><p className="release-guide-proof">基线状态应成功返回版本 1；网站仍应健康。异常时停下排查，不删除历史或自动重试。</p><div className="release-guide-actions"><button className="secondary" disabled={!selectedReady || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestDeploymentTask('DATABASE_BASELINE_STATUS')}>检查基线状态</button><button className="secondary" disabled={!selectedReady || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestDeploymentTask('SITE_HEALTH')}>检查站点健康</button></div></li>
              </ol>
              <p className="release-guide-boundary">准备步骤不切换生产，也不自动删除产物。第七步切换失败会尝试恢复旧应用，但不会回滚数据库DDL或覆盖业务数据。原始回执、审批与审计保留在“运行记录”。</p>
            </section>
            <section className="release-guide" aria-label="真正上线">
              <div className="release-guide-intro"><strong>07 · 真正上线（会短暂影响访问）</strong><p>校验候选、近期备份和当前生产身份 → 执行向后兼容迁移 → 只重建 app → 刷新 Nginx → 检查容器、首页和浏览页 → 同步生产 app.jar / Dockerfile。健康失败自动恢复旧镜像和文件；不恢复数据库、不删除备份。</p><p>这次须重新准备候选及镜像，使它包含新的发布维护入口；旧候选不能直接上线。Compose、网络、卷和 Nginx 配置变更不属于应用上线，配置不一致会明确拦截。</p></div>
              <div className="release-guide-actions"><button className="secondary" disabled={!selectedReady || props.busy} onClick={() => void requestDeploymentTask('DATABASE_SCHEMA')}>核查数据库结构</button><button className="secondary" disabled={!selectedReady || props.busy} onClick={() => void requestDeploymentTask('DATABASE_BASELINE_STATUS')}>检查版本历史</button><button className="secondary" disabled={!selectedReady || props.busy} onClick={() => void requestDeploymentTask('RELEASE_STATUS')}>读取当前线上版本</button></div>
              {productionStatus?.target === expectedTarget && <p>当前镜像：<code>{productionStatus.currentImageId || '未确认'}</code> · 容器健康：{productionStatus.appHealth || '未确认'}</p>}
              {!publishState.ready && <ul>{publishState.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul>}
              <details className="release-identity"><summary>查看本次上线的八项绑定身份</summary><pre>{JSON.stringify(publishState.args, null, 2)}</pre></details>
              {!publishToolReady && <p className="release-guide-warning">先在 Agent Builder 勾选“受审上线并验证恢复”，发布新 Agent 版本，再选择该版本。</p>}
              <label className="release-guide-warning"><input type="checkbox" checked={publishAcknowledged} onChange={event => setPublishAcknowledged(event.target.checked)} />我已确认备份和候选，接受短暂中断；失败时只自动恢复应用，数据库状态不明需停止排查。</label>
              <button className="danger" disabled={!publishToolReady || !publishState.ready || !publishAcknowledged || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestPublish()}>请求真正上线（下一步仍需审批）</button>
              {publishResult?.target === expectedTarget && <div className={`fixed-tool-reply ${publishResult.deployed && publishResult.successful ? 'fixed-tool-reply--success' : 'fixed-tool-reply--failed'}`}><strong>{publishResult.deployed && publishResult.successful ? '已上线且健康验证通过' : publishResult.rolledBack ? '上线未成功，旧应用已恢复并验证健康' : '上线未确认，停止重试并检查线上状态'}</strong><p>最终确认的镜像：{publishResult.currentImageId || '未确认'}。{publishResult.manualInterventionRequired ? '需要人工介入；不要直接重试。' : ''}</p></div>}
              <p>08 · 上线后点击“读取当前线上版本”和“检查站点健康”，再到网站验收实际改动。同样的页面内容不会因为重新打包而自动变化。</p>
              <button className="secondary" disabled={!selectedReady || props.busy} onClick={() => void requestDeploymentTask('SITE_HEALTH')}>检查上线后站点健康</button>
            </section>
            <details className="release-advanced" open={props.deployment?.status !== 'READY'}><summary>高级配置、单项诊断与技术结果</summary><div className="release-advanced-content">
            <form className="remote-deployment-form" onSubmit={submitDeployment}>
              <label>本地源码根<input required value={props.deploymentForm.localSourceRoot} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, localSourceRoot: e.target.value })} /></label>
              <label>远程部署根<input required value={props.deploymentForm.remoteDeployRoot} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, remoteDeployRoot: e.target.value })} /></label>
              <label>远程备份根<input required value={props.deploymentForm.remoteBackupRoot} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, remoteBackupRoot: e.target.value })} /></label>
              <label>本地 Compose 文件<input required value={props.deploymentForm.localComposeFile} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, localComposeFile: e.target.value })} /></label>
              <label>远程 Compose 文件<input required value={props.deploymentForm.composeFile} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, composeFile: e.target.value })} /></label>
              <label>Compose 项目<input required value={props.deploymentForm.composeProject} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, composeProject: e.target.value })} /></label>
              <label>Nginx 配置<input required value={props.deploymentForm.nginxConfig} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, nginxConfig: e.target.value })} /></label>
              <label>固定健康地址<input required value={props.deploymentForm.healthUrl} onChange={(e) => props.onDeploymentFormChange({ ...props.deploymentForm, healthUrl: e.target.value })} /></label>
              <div><button className="secondary" disabled={props.busy}>保存 Profile</button><button className="ghost" type="button" disabled={props.busy || !props.deployment?.configured || !props.sshStatus?.passwordConfigured} onClick={() => void props.onTestDeployment()}>只读检查目标</button></div>
            </form>
            {props.deployment?.lastError && <p className="error-text">{props.deployment.lastError}</p>}
            <div className="remote-service-ghosts">{['nginx', 'app', 'mysql', 'phpmyadmin'].map((name) => <i key={name}>{name}<small>{props.deployment?.status === 'READY' ? '固定服务' : '等待目标检查'}</small></i>)}</div>
            <div className="remote-deployment-tasks">{deploymentTasks.map((item) => <article key={item.id}><div><b>{item.title}</b><code>{item.id}</code></div><p>{item.detail}</p><button className="primary" disabled={!selectedReady || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestDeploymentTask(item.id)}>请求诊断</button></article>)}</div>
            {deploymentResult && <div className="remote-deployment-result"><div><strong>{deploymentResult.task}</strong><span className={`badge badge--${deploymentResult.successful ? 'completed' : 'failed'}`}>{deploymentResult.successful ? 'SUCCESS' : `EXIT ${deploymentResult.exitCode}`}</span></div>{deploymentResult.task === 'DATABASE_SCHEMA' && <p>{deploymentResult.schemaComplete ? `结构 SHA-256：${deploymentResult.schemaSha256}。仅完成结构采集，未登记基线，也不代表已兼容新版本。` : '结构采集未完整完成，不能用于基线登记或发布。'}</p>}<pre>{deploymentResult.output || '(诊断没有输出)'}</pre><footer>{deploymentResult.durationMs} ms · {deploymentResult.target}{deploymentResult.outputTruncated ? ' · 输出已截断' : ''}</footer></div>}
            <div className="remote-backup-card"><div><span>CREATE-ONLY / HIGH</span><h4>发布前固定备份</h4><p>新建不可覆盖的时间戳目录，固定备份数据库、uploads、部署文件、受保护 .env、镜像与服务清单；不会删除旧备份，也不会恢复数据库。请从上方第 3 步执行。</p></div></div>
            {backupResult && <div className="remote-backup-result"><div><strong>{backupResult.backupId || 'BACKUP FAILED'}</strong><span className={`badge badge--${backupResult.successful ? 'completed' : 'failed'}`}>{backupResult.successful ? 'VERIFIED' : `EXIT ${backupResult.exitCode}`}</span></div>{backupResult.successful ? <dl><div><dt>备份路径</dt><dd>{backupResult.backupPath}</dd></div><div><dt>数据库</dt><dd>{formatCompactBytes(backupResult.databaseBytes)}</dd></div><div><dt>Uploads</dt><dd>{formatCompactBytes(backupResult.uploadsBytes)}</dd></div><div><dt>Manifest</dt><dd>{backupResult.manifestSha256}</dd></div></dl> : <pre>{backupResult.output || '备份未完成'}</pre>}<footer>{backupResult.durationMs} ms · 固定文件 {backupResult.fileCount || 0} 项 · 仅创建、不覆盖</footer></div>}
            <div className="remote-backup-card remote-restore-card"><div><span>ISOLATED RESTORE DRILL / HIGH</span><h4>备份恢复材料演练</h4><p>自动选择最新合格备份，在 restore-drills 下新建隔离目录，重新校验并展开数据库、uploads 与部署文件；不会导入数据库、替换生产文件、启动容器或删除备份。</p></div><button className="danger" disabled={!restoreDrillReady || props.busy || props.deployment?.status !== 'READY'} onClick={() => void requestRestoreDrill()}>请求恢复演练</button></div>
            {!restoreDrillReady && selectedReady && <p className="remote-tool-hint">当前 Agent 版本尚未包含恢复演练工具，请在 Agent Builder 发布包含该工具的新版本后再执行。</p>}
            {restoreDrillResult && <div className="remote-backup-result remote-restore-result"><div><strong>{restoreDrillResult.drillId || 'RESTORE DRILL FAILED'}</strong><span className={`badge badge--${restoreDrillResult.successful ? 'completed' : 'failed'}`}>{restoreDrillResult.successful ? 'MATERIALIZED' : `EXIT ${restoreDrillResult.exitCode}`}</span></div>{restoreDrillResult.successful ? <dl><div><dt>来源备份</dt><dd>{restoreDrillResult.backupId}</dd></div><div><dt>隔离目录</dt><dd>{restoreDrillResult.drillPath}</dd></div><div><dt>数据库文件</dt><dd>{formatCompactBytes(restoreDrillResult.databaseBytes)}（未导入）</dd></div><div><dt>展开 Uploads</dt><dd>{formatCompactBytes(restoreDrillResult.restoredUploadsBytes)}</dd></div><div><dt>演练摘要</dt><dd>{restoreDrillResult.drillSha256}</dd></div></dl> : <pre>{restoreDrillResult.output || '恢复演练未完成'}</pre>}<footer>{restoreDrillResult.durationMs} ms · 文件 {restoreDrillResult.restoredFileCount || 0} 项 · 生产目录未修改 · 数据库未导入</footer></div>}
            <div className="remote-backup-card"><div><span>LOCAL BUILD + IMMUTABLE STAGING / HIGH</span><h4>准备不可变发布候选</h4><p>平台从固定本地源码运行 Maven 测试与打包，只接受 target/app.jar；随后上传固定部署材料到全新的候选目录并核对摘要。不会读取 .env、构建镜像或修改生产目录。请从上方第 1 步执行。</p></div></div>
            {!releaseCandidateReady && selectedReady && <p className="remote-tool-hint">当前 Agent 版本尚未包含“准备不可变发布候选”，请在 Agent Builder 发布包含该工具的新版本后再执行。</p>}
            <div className="remote-backup-card"><div><span>ISOLATED IMAGE BUILD / HIGH</span><h4>构建候选应用镜像</h4><p>绑定明确候选与清单摘要，只生成独立镜像，不切换生产、不重启网站。使用 512 MiB / 0.5 CPU 的临时构建器；可能下载构建器和基础镜像。旧格式候选需重新准备。请从上方第 2 步执行。</p></div></div>
            {!releaseImageReady && selectedReady && <p className="remote-tool-hint">请在 Agent Builder 勾选“构建候选应用镜像（build_release_candidate_image）”并发布新版本。</p>}
            <div className="remote-backup-card"><div><span>EXPLICIT DATABASE BASELINE / HIGH</span><h4>登记受审数据库基线</h4><p>必须使用本轮网站源码的新候选镜像与30分钟内新备份。仅创建 Flyway 版本1历史，不建业务表、不迁移、不重启网站；登记期间短暂阻止业务写入。请避开人工 DDL 操作，并从上方第 5 步核对身份后执行。</p></div></div>
            {!baselineReady && selectedReady && <p className="remote-tool-hint">请在 Agent Builder 勾选“登记受审数据库基线（adopt_remote_database_baseline）”并发布新版本。</p>}
            {releaseImageResult && <div className="remote-backup-result"><div><strong>{releaseImageResult.releaseId}</strong><span className={`badge badge--${releaseImageResult.successful ? 'completed' : 'failed'}`}>{releaseImageResult.successful ? 'IMAGE READY' : `EXIT ${releaseImageResult.exitCode}`}</span></div><dl><div><dt>独立镜像标签</dt><dd>{releaseImageResult.imageTag}</dd></div><div><dt>镜像 ID</dt><dd>{releaseImageResult.imageId || '未生成已验证镜像'}</dd></div><div><dt>清单摘要</dt><dd>{releaseImageResult.manifestSha256}</dd></div><div><dt>构建记录目录</dt><dd>{releaseImageResult.buildAttemptPath}</dd></div></dl><pre>{releaseImageResult.output}</pre><footer>{releaseImageResult.durationMs} ms · 未切换生产 · 未重启服务{releaseImageResult.outputTruncated ? ' · 输出已截断' : ''}</footer></div>}
            {releaseCandidateResult && <div className="remote-backup-result"><div><strong>{releaseCandidateResult.releaseId || releaseCandidateResult.stage}</strong><span className={`badge badge--${releaseCandidateResult.successful ? 'completed' : 'failed'}`}>{releaseCandidateResult.successful ? 'STAGED' : `EXIT ${releaseCandidateResult.exitCode}`}</span></div>{releaseCandidateResult.successful ? <dl><div><dt>固定制品</dt><dd>{releaseCandidateResult.artifactPath} · {formatCompactBytes(releaseCandidateResult.artifactBytes)}</dd></div><div><dt>候选目录</dt><dd>{releaseCandidateResult.candidatePath}</dd></div><div><dt>制品摘要</dt><dd>{releaseCandidateResult.artifactSha256}</dd></div><div><dt>清单摘要</dt><dd>{releaseCandidateResult.manifestSha256}</dd></div></dl> : <pre>{releaseCandidateResult.output || '候选版本未完成'}</pre>}<footer>{releaseCandidateResult.durationMs} ms · 生产目录未修改 · 未构建镜像{releaseCandidateResult.outputTruncated ? ' · 输出已截断' : ''}</footer></div>}
            </div></details>
          </div>}
          {tab === 'output' && <div className="remote-output"><div className="remote-output-head"><div><span>CONTROLLED TASK OUTPUT</span><strong>{task ? `${task.task} · ${task.path}` : '等待固定任务'}</strong></div>{task && <span className={`badge badge--${task.successful ? 'completed' : 'failed'}`}>{task.successful ? 'SUCCESS' : `EXIT ${task.exitCode}`}</span>}</div>{task ? <><pre>{task.output || '(任务没有输出)'}</pre><footer>{task.durationMs} ms · {task.target}{task.outputTruncated ? ' · 输出已截断' : ''}</footer></> : <Empty text="从“任务”标签请求 Git、Maven 或 npm 固定任务" />}</div>}
        </div>
      </main>

      <aside className="remote-agent-pane">
        <div className="remote-pane-title"><div><span>AGENT</span><strong>操作助手</strong></div><i className={props.busy ? 'busy' : ''} /></div>
        <div className="remote-agent-feed">
          {props.pendingApproval && <aside className="approval-card remote-approval" role="alert"><div className="approval-heading"><strong>等待一次性审批</strong><b>{props.approvalSecondsLeft > 0 ? `${props.approvalSecondsLeft} 秒` : '已过期'}</b></div><p>工具：<code>{props.pendingApproval.toolName}</code></p><p>目标：{props.pendingApproval.targetEnvironment}</p><pre>{props.pendingApproval.argumentsJson}</pre><small>参数摘要 {props.pendingApproval.argumentsSha256.slice(0, 16)}…</small><div><button className="danger" disabled={props.approvalBusy || props.approvalSecondsLeft <= 0} onClick={() => void props.onDecideApproval(false)}>拒绝</button><button className="primary" disabled={props.approvalBusy || props.approvalSecondsLeft <= 0} onClick={() => void props.onDecideApproval(true)}>批准一次</button></div></aside>}
          {props.steps.length > 0 && <aside className="run-steps remote-steps"><strong>本次运行步骤（点开看原始记录）</strong>{props.steps.map((step) => <details key={step.id}><summary>#{step.stepNumber} {step.stepType}<span className={`step-status step-status--${step.status.toLowerCase()}`}>{step.status}</span></summary>{step.toolName && <code>{step.toolName}</code>}{step.outputText && <pre>{step.outputText}</pre>}</details>)}</aside>}
          {props.messages.length === 0 && props.steps.length === 0 ? <Empty text={selectedReady ? '按中间的分步引导操作；执行后在这里查看结果' : '浏览无需 Agent；执行任务前请选择 Agent 版本'} /> : <>{hiddenMessageCount > 0 && <p className="remote-history-note">已收起更早的 {hiddenMessageCount} 条会话消息，完整记录仍保留在运行记录中。</p>}{visibleMessages.map((message, index) => <article className={`remote-message remote-message--${message.role}`} key={`${hiddenMessageCount}-${index}`}><span>{message.role === 'user' ? 'YOU' : 'AGENT'}</span>{message.content ? message.role === 'assistant' ? <FixedToolReply content={message.content} /> : <UserToolRequest content={message.content} /> : <p><i className="typing">正在处理</i></p>}</article>)}</>}
        </div>
        <form className="remote-agent-composer" onSubmit={submitAssistant}><textarea rows={3} value={assistantInput} onChange={(event) => setAssistantInput(event.target.value)} placeholder="让 Agent 检查文件、解释结果或提出受控操作……" /><div><button className="primary" disabled={!selectedReady || props.busy || !assistantInput.trim()}>{props.busy ? '运行中' : '发送'}</button>{props.busy && props.currentRunId && <button className="danger" type="button" disabled={props.cancelBusy} onClick={() => void props.onCancel()}>{props.cancelBusy ? '停止中' : '停止'}</button>}</div></form>
      </aside>
    </div>
    <footer className="remote-statusbar"><span><i className={props.sshStatus?.status === 'READY' ? 'ready' : ''} />{props.sshStatus?.status ?? 'NOT_CONFIGURED'}</span><span>{props.sshStatus?.host ? `${props.sshStatus.username}@${props.sshStatus.host}:${props.sshStatus.port}` : '无 SSH 目标'}</span><span>{props.busy ? 'AgentRun 运行中' : '就绪'}</span><span>任意 Shell：禁用</span></footer>
  </section>
}

function latestToolJson<T>(steps: RunStep[], toolName: string): T | undefined {
  for (let index = steps.length - 1; index >= 0; index--) {
    const step = steps[index]
    if (step.toolName !== toolName || !step.outputText) continue
    try { const result = JSON.parse(step.outputText) as T; return step.observedAt && result && typeof result === 'object'
      ? { ...result, observedAt: step.observedAt } : result } catch { return undefined }
  }
  return undefined
}

function latestDeploymentTask(steps: RunStep[], task: string): TaskResult | undefined {
  for (let index = steps.length - 1; index >= 0; index--) {
    const step = steps[index]
    if (step.toolName !== 'inspect_remote_deployment' || !step.outputText) continue
    try { const result = JSON.parse(step.outputText) as TaskResult; if (result.task === task) return { ...result, observedAt: step.observedAt } } catch { /* older malformed record */ }
  }
  return undefined
}

const fixedToolLabels: Record<string, string> = {
  prepare_release_candidate: '准备候选', build_release_candidate_image: '构建候选镜像',
  prepare_remote_deployment_backup: '创建发布前备份', inspect_remote_deployment: '只读部署诊断',
  adopt_remote_database_baseline: '登记数据库基线', verify_remote_deployment_backup_restore: '备份恢复材料演练',
  publish_remote_release: '受审上线并验证恢复',
}

function UserToolRequest({ content }: { content: string }) {
  const tool = Object.keys(fixedToolLabels).find((name) => content.startsWith(`请只使用 ${name} 工具`))
  if (!tool) return <p>{content}</p>
  return <div className="fixed-tool-request"><strong>已请求：{fixedToolLabels[tool]}</strong><details><summary>查看原始请求</summary><p>{content}</p></details></div>
}

function FixedToolReply({ content }: { content: string }) {
  const receipt = fixedToolReceipt(content)
  if (!receipt) return <MarkdownMessage content={content} />
  const success = fixedToolSucceeded(receipt)
  const title = receipt.task === 'PUBLISH_RELEASE' ? '受审上线并验证恢复' : typeof receipt.task === 'string' ? deploymentTasks.find((task) => task.id === receipt.task)?.title ?? String(receipt.task)
    : isBaselineRegistrationReceipt(receipt) ? '登记数据库基线'
      : 'releaseId' in receipt && 'imageId' in receipt ? '构建候选镜像'
      : 'backupId' in receipt && 'drillId' in receipt ? '备份恢复材料演练'
        : 'backupId' in receipt && 'databaseBytes' in receipt ? '创建发布前备份'
          : 'releaseId' in receipt ? '准备候选' : '固定任务'
  const identifier = [receipt.releaseId, receipt.imageId, receipt.backupId, receipt.schemaSha256].find((item) => typeof item === 'string')
  return <div className={`fixed-tool-reply ${success ? 'fixed-tool-reply--success' : 'fixed-tool-reply--failed'}`}>
    <strong>{title}：{success ? '执行成功' : '未成功'}</strong>
    <p>工具结果：successful={String(receipt.successful)}，exitCode={String(receipt.exitCode ?? '未知')}{typeof receipt.stage === 'string' ? `，阶段 ${receipt.stage}` : ''}。</p>
    {identifier && <p className="fixed-tool-identifier">关键标识：{String(identifier)}</p>}
    {receipt.task === 'DATABASE_SCHEMA' && success && <p>数据库结构核查通过；本次只读操作没有登记基线。</p>}
    {receipt.task === 'PUBLISH_RELEASE' && <p>{receipt.deployed === true && success ? '已切换候选，健康验证通过。' : receipt.rolledBack === true ? '上线失败；旧应用已恢复并验证健康，数据库扩展不会自动撤销。' : '不能确认上线；先查线上版本和数据库历史。'}{receipt.manualInterventionRequired === true ? '需要人工介入，不要直接重试。' : ''}</p>}
    {receipt.outputTruncated === true && <p>输出已截断，请到运行记录查看完整的有界回执。</p>}
    {!success && <p>请先查看失败原因及可能残留的产物，不要直接重试下一步。</p>}
    <details><summary>技术详情与原始 JSON</summary><pre>{JSON.stringify(receipt, null, 2)}</pre></details>
  </div>
}

function formatCompactBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
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
