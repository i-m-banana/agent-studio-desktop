import { useEffect, useState } from 'react'
import { ToolResultCard } from './ToolResultCard'
import { mysqlVerifiedForSource, type MysqlVerification } from './localVerification'

export function ReleaseVerificationHint({source,required,records,busy,onVerify}:{source?:string;required?:boolean;records:MysqlVerification[];busy:boolean;onVerify:()=>void}) {
  if(required===undefined)return <p className="hint">正在核对发布所需验证…</p>
  if(!required || !source)return null
  return mysqlVerifiedForSource(source,records) ? <p className="hint">当前预览源码 {source.slice(0,8)} 的 MySQL 合成验证已通过，隔离资源已清理。</p>
    : <div role="status"><p className="error-text">当前预览源码 {source.slice(0,8)} 尚未通过 MySQL 合成验证。请使用本地验证工具；远程部署助手的任务不能替代它。</p><button className="secondary" disabled={busy} onClick={onVerify}>去 06 验证 MySQL</button><p className="hint">验证通过后回到 07 准备上线。已有预览和人工验收保留，源码未变化时无需重新预览。</p></div>
}

export type WorkflowAdvance = { revision: number; operationId: string; riskAcknowledged: boolean; recoveryReviewed: boolean }
type WorkflowView = {
  workflow: { id: string; projectId: string; conversationId: string; agentVersionId: string; revision: number; status: string; target: string; sourceSha256: string; activeRunId?: string; message: string; createdAt: string }
  next: { phase: string; title: string; purpose: string; blocked: boolean; reason: string }
  members: { id: string; phase: string; runId: string }[]
  evidence: Record<string, { phase: string; status: string; receipt?: Record<string, unknown>; at: string }>
}
const phaseNames: Record<string, string> = { CANDIDATE: '候选', IMAGE: '镜像', BACKUP: '备份', SCHEMA: '结构核查', HISTORY: '版本历史', STATUS: '线上身份', PUBLISH: '正式上线', POST_STATUS: '上线后版本', POST_HEALTH: '上线后健康', RECOVERY_STATUS: '异常后版本', RECOVERY_HISTORY: '异常后历史', RECOVERY_HEALTH: '异常后健康' }
async function read<T>(url: string, options?: RequestInit): Promise<T> {
  const r = await fetch(url, options); const data = await r.json(); if (!r.ok) throw new Error(data.message ?? '发布任务读取失败'); return data
}
export function ReleaseWorkflowPanel({ projectId, versionId, busy, onAdvance, onObserve, onVerifyMysql }: {
  projectId?: string; versionId: string; busy: boolean
  onAdvance: (id: string, request: WorkflowAdvance) => Promise<void>; onObserve: (conversation: string) => Promise<void>
  onVerifyMysql:(project:string)=>void
}) {
  const [items, setItems] = useState<WorkflowView[]>([])
  const [selected, setSelected] = useState('')
  const [previews, setPreviews] = useState<{ id: string; state: string; sourceSha256: string }[]>([])
  const [previewId, setPreviewId] = useState('')
  const [previewAccepted, setPreviewAccepted] = useState(false)
  const [mysqlRequired,setMysqlRequired]=useState<boolean>()
  const [mysqlRecords,setMysqlRecords]=useState<MysqlVerification[]>([])
  const [risk, setRisk] = useState(false), [reviewed, setReviewed] = useState(false)
  const [error, setError] = useState(''), [loading, setLoading] = useState(false)
  useEffect(() => {
    let disposed = false
    setItems([]); setSelected(''); setError(''); setPreviewAccepted(false); setPreviews([]); setPreviewId('')
    setMysqlRequired(undefined);setMysqlRecords([])
    if (!projectId) return
    const load = async () => {
      try {
        const data = await read<WorkflowView[]>(`/api/release-workflows?projectId=${encodeURIComponent(projectId)}`)
        if (!disposed) { setItems(data); setSelected(current => current && data.some(v => v.workflow.id === current) ? current : data.find(v => v.workflow.status !== 'CLOSED')?.workflow.id ?? data[0]?.workflow.id ?? '') }
        const [list,config,records] = await Promise.all([
          read<typeof previews>(`/api/local-previews?projectId=${encodeURIComponent(projectId)}`),
          read<{mysqlEnabled:boolean}>(`/api/local-projects/${encodeURIComponent(projectId)}/runtime`),
          read<MysqlVerification[]>(`/api/local-projects/${encodeURIComponent(projectId)}/mysql-verifications`),
        ])
        if (!disposed) { setPreviews(list.filter(v => ['READY','STOPPED'].includes(v.state))); setPreviewId(current => current || list.find(v => ['READY','STOPPED'].includes(v.state))?.id || '');setMysqlRequired(config.mysqlEnabled);setMysqlRecords(records) }
      } catch (e) { if (!disposed) setError(String(e)) }
    }
    void load(); const timer = setInterval(() => void load(), 5000)
    return () => { disposed = true; clearInterval(timer) }
  }, [projectId])
  const active = items.find(v => v.workflow.id === selected)
  const previewSource=previews.find(p=>p.id===previewId)?.sourceSha256
  const mysqlReady=mysqlRequired===false || (mysqlRequired===true && mysqlVerifiedForSource(previewSource,mysqlRecords))
  useEffect(() => { setRisk(false); setReviewed(false) }, [selected, active?.workflow.revision])
  async function perform(action: () => Promise<void>) { setLoading(true); setError(''); try { await action() } catch (e) { setError(e instanceof Error ? e.message : String(e)) } finally { setLoading(false) } }
  async function create() {
    if (!projectId || !versionId || !previewAccepted) return
    await perform(async () => {
      const v = await read<WorkflowView>('/api/release-workflows', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ projectId, agentVersionId: versionId, previewId, previewAccepted }) })
      setItems(current => [v, ...current]); setSelected(v.workflow.id)
      await onAdvance(v.workflow.id, { revision: v.workflow.revision, operationId: crypto.randomUUID(), riskAcknowledged: false, recoveryReviewed: false })
    })
  }
  async function advance() {
    if (!active) return
    if (active.next.phase === 'REVIEW') { await finish('review'); return }
    await perform(() => onAdvance(active.workflow.id, { revision: active.workflow.revision, operationId: crypto.randomUUID(), riskAcknowledged: risk, recoveryReviewed: reviewed }))
  }
  async function finish(action: 'accept' | 'close' | 'review') {
    if (!active) return
    if (action === 'close' && !window.confirm('结束此发布任务？已执行的操作不会撤销，候选、日志和所有备份保留。')) return
    await perform(async () => {
      const updated = await read<WorkflowView>(`/api/release-workflows/${active.workflow.id}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision: active.workflow.revision }) })
      setItems(current => current.map(v => v.workflow.id === updated.workflow.id ? updated : v))
    })
  }
  return <section className="workflow-panel" aria-label="本次发布任务">
    <div className="section-head"><h3>本次发布</h3>{items.length > 0 && <select aria-label="选择发布任务" value={selected} disabled={loading} onChange={e => setSelected(e.target.value)}>{items.map(v => <option key={v.workflow.id} value={v.workflow.id}>{new Date(v.workflow.createdAt).toLocaleString()} · {v.workflow.status === 'CLOSED' ? '已结束' : '处理中'}</option>)}</select>}</div>
    {!projectId ? <p>先在工具栏选择本地项目，再选择具有发布能力的部署助手。</p> : <>
      {!versionId&&!active&&<p className="hint">先在“执行 Agent”中选择部署助手，再开始发布准备。</p>}
      {active && <><p className="hint">目标：{active.workflow.target}</p><div className="workflow-next" role="status"><strong>下一步：{active.next.title}</strong><p>{active.next.purpose}</p><p className="hint">{active.workflow.message}</p></div>
        {active.next.phase === 'OBSERVE' ? <button className="secondary" disabled={loading} onClick={() => void perform(() => onObserve(active.workflow.conversationId))}>恢复观察原运行</button> : <>
          {active.next.phase === 'PUBLISH' && <label className="workflow-check"><input type="checkbox" checked={risk} onChange={e => setRisk(e.target.checked)} />接受短暂中断和兼容迁移风险；应用恢复不会撤销数据库扩展，下一步仍需新的审批。</label>}
          {active.next.phase === 'REVIEW' && <label className="workflow-check"><input type="checkbox" checked={reviewed} onChange={e => setReviewed(e.target.checked)} />我已核对异常后的三项只读结果并处理异常；不会复用旧批准。</label>}
          {active.next.phase === 'ACCEPT' ? <button className="primary" disabled={busy || loading} onClick={() => void finish('accept')}>我已查看网站，本次需求验收通过</button> : active.next.phase==='RECONCILE'? <button className="secondary" disabled={busy||loading} onClick={()=>void finish('close')}>已查看网站，结束任务并保留未确认回执</button> : !active.next.blocked && <button className={active.next.phase === 'PUBLISH' ? 'danger' : 'primary'} disabled={busy || loading || active.next.phase === 'PUBLISH' && !risk || active.next.phase === 'REVIEW' && !reviewed} onClick={() => void advance()}>{active.next.phase === 'PUBLISH' ? '确认上线（仍需审批）' : active.next.phase === 'REVIEW' ? '核对后继续准备' : '继续准备（逐项审批）'}</button>}
        </>}
        <details className="workflow-evidence"><summary>阶段结果与排查</summary>{Object.entries(active.evidence).map(([phase,e]) => <div key={phase}><h4>{phaseNames[phase] ?? phase} · {e.status}</h4>{e.receipt ? <ToolResultCard receipt={e.receipt} createdAt={e.at} /> : <p>没有完整回执，不能确认执行结果。</p>}</div>)}<button className="secondary" onClick={() => void perform(() => onObserve(active.workflow.conversationId))}>查看来源会话、运行和审计</button><details><summary>技术身份与材料关联</summary><pre>{JSON.stringify(active,null,2)}</pre></details></details>
        {active.workflow.status !== 'CLOSED' && <button className="ghost" disabled={busy || loading || active.next.phase === 'OBSERVE'} onClick={() => void finish('close')}>结束任务并保留材料</button>}
      </>}
      {(!active || active.workflow.status === 'CLOSED') && <div className="workflow-create"><h4>开始新的日常发布</h4><label className="field">已验收的预览<select value={previewId} onChange={e => { setPreviewId(e.target.value); setPreviewAccepted(false) }}><option value="">先在对话项目面板启动并验收预览</option>{previews.map((p,i) => <option key={p.id} value={p.id}>预览 {i+1} · {p.state === 'READY' ? '就绪' : '已停止'} · 源码 {p.sourceSha256.slice(0,8)}</option>)}</select></label><label className="workflow-check"><input type="checkbox" checked={previewAccepted} onChange={e => setPreviewAccepted(e.target.checked)} />我已查看所选预览，确认本次需求符合预期；MySQL 覆盖以单独验证结果为准。</label><ReleaseVerificationHint source={previewSource} required={mysqlRequired} records={mysqlRecords} busy={busy||loading} onVerify={()=>onVerifyMysql(projectId)} /><button className="primary" disabled={busy || loading || !previewId || !previewAccepted || !versionId || !mysqlReady} onClick={() => void create()}>准备上线（逐项审批）</button></div>}
    </>}
    <p className="hint">材料由平台传递。已有任务沿用创建时的助手，新的选择只影响新任务。刷新和重启不会续执行或复用审批，过期只更新对应项；首次基线另在接入流程处理。</p>
    {error && <p className="error-text" role="alert">{error}</p>}
  </section>
}
