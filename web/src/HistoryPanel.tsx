import { useEffect, useRef, useState } from 'react'
import type { ReleaseTask } from './releaseHistory'
import { releaseStatusLabels } from './releaseHistory'

export type ConversationSummary = { id: string; agentVersionId: string; updatedAt: string; preview?: string; messageCount: number; activeRunId?: string }
const names: Record<string,string> = { prepare_release_candidate: '准备候选', build_release_candidate_image: '构建镜像',
  prepare_remote_deployment_backup: '创建备份', inspect_remote_deployment: '只读部署核查',
  adopt_remote_database_baseline: '登记基线', verify_remote_deployment_backup_restore: '恢复材料演练', publish_remote_release: '受审上线' }
export function HistoryPanel(props: {
  conversations: ConversationSummary[]; tasks: ReleaseTask[]; conversationId?: string; candidateId?: string
  target: string; busy: boolean; onOpen: (id:string)=>void; onNew: ()=>void; onCandidate:(id:string)=>void
  onRefresh:()=>void; onMore:()=>void; onRun:(id:string)=>void
  onTrash:(ids:string[],restore:boolean)=>Promise<void>; onLoadTrash:()=>Promise<ConversationSummary[]>; onError:(message:string)=>void
}) {
  const [managing,setManaging] = useState(false)
  const [trash,setTrash] = useState<ConversationSummary[]>()
  const [selected,setSelected] = useState<string[]>([])
  const [saving,setSaving] = useState(false)
  const [error,setError] = useState('')
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { if (managing) dialog.current?.showModal() },[managing])
  const records = trash ?? props.conversations
  async function changeHistory() {
    setSaving(true); setError('')
    try {
      await props.onTrash(selected,Boolean(trash)); setSelected([])
      if (trash) setTrash(await props.onLoadTrash())
    } catch (e) { setError(String(e)); props.onError(String(e)) }
    finally { setSaving(false) }
  }
  const candidates = props.tasks.filter(t => t.toolName === 'prepare_release_candidate' && t.status === 'SUCCEEDED' && t.target === props.target && props.conversations.some(c => c.id === t.conversationId))
  const visible = props.tasks.filter(t => !props.conversationId || t.conversationId === props.conversationId || t.id === props.candidateId)
  return <div className="history-compact" aria-label="历史会话">
    <select aria-label="历史会话" value={props.conversationId ?? ''} disabled={props.busy} onChange={e => e.target.value ? props.onOpen(e.target.value) : props.onNew()}>
      <option value="">历史会话</option>{props.conversations.map(c => <option key={c.id} value={c.id}>{new Date(c.updatedAt).toLocaleString()} · {(c.preview ?? '未命名会话').slice(0,32)}{c.activeRunId ? ' · 进行中' : ''}</option>)}</select>
    <button className="ghost" disabled={props.busy} onClick={props.onNew}>新会话</button>
    <button className="ghost" disabled={props.busy} onClick={() => { setManaging(true); setTrash(undefined); setSelected([]); setError('') }}>管理</button>
    {managing && <dialog className="history-dialog" ref={dialog} onClose={() => setManaging(false)} onCancel={e => { if (saving) e.preventDefault() }}>
      <header><h2>管理历史会话</h2><button className="ghost" autoFocus disabled={saving} aria-label="关闭历史管理" onClick={() => dialog.current?.close()}>关闭</button></header>
      <p>删除后移到“已删除”，可以恢复。发布执行记录和服务器上的文件不会被删除。</p>
      {error && <p role="alert">{error}</p>}
      <div className="history-actions"><button className="secondary" disabled={saving} onClick={() => { setTrash(undefined); setSelected([]) }}>会话清单</button>
        <button className="secondary" disabled={saving} onClick={async () => { setSaving(true); setError(''); try { setTrash(await props.onLoadTrash()); setSelected([]) } catch(e) { setError(String(e)); props.onError(String(e)) } finally { setSaving(false) } }}>已删除</button>
        <button className="secondary" disabled={saving} onClick={() => setSelected(records.filter(c => !c.activeRunId).slice(0,200).map(c => c.id))}>选中当前列表</button>
        <button className="ghost" disabled={saving} onClick={() => setSelected([])}>取消选择</button>
        <button className={trash ? 'secondary' : 'danger'} disabled={saving || !selected.length} onClick={() => void changeHistory()}>{saving ? '处理中…' : `${trash ? '恢复' : '删除'}选中的 ${selected.length} 条`}</button></div>
      <p>{trash ? '已删除' : '会话清单'} · 当前显示 {records.length} 条</p>
      <div className="history-list">{records.map(c => <label key={c.id}><input type="checkbox" disabled={saving || Boolean(c.activeRunId) || (!selected.includes(c.id) && selected.length >= 200)} checked={selected.includes(c.id)} onChange={e => setSelected(e.target.checked ? [...selected,c.id] : selected.filter(id => id !== c.id))}/><span>{new Date(c.updatedAt).toLocaleString()} · {c.preview ?? '未命名会话'}<small>{c.messageCount} 条消息{c.activeRunId ? ' · 执行中，暂不能删除' : ''}</small></span></label>)}{!records.length && <p>这里还没有会话。</p>}</div>
      {!trash && <button className="secondary" disabled={saving} onClick={props.onMore}>加载更早的会话</button>}
    <details className="history-details"><summary>更多选项与发布记录（可跳过）</summary>
      <p>这里用于排查发布问题。查看不会重新执行操作，日常回看聊天无需使用。</p>
      <div className="history-controls"><button className="secondary" onClick={props.onRefresh}>更新会话清单</button>
      <label>使用其他会话中的发布材料<select value={props.candidateId ?? ''} disabled={props.busy} onChange={e => props.onCandidate(e.target.value)}><option value="">仅使用当前会话的材料</option>
        {candidates.map(c => <option key={c.id} value={c.id}>{new Date(c.observedAt).toLocaleString()} · {c.releaseId}</option>)}</select></label></div>
    {visible.length === 0 && <p>尚无发布阶段记录。</p>}{visible.map(t => <article className="history-stage" key={t.id}>
      <strong>{names[t.toolName] ?? t.toolName} · {releaseStatusLabels[t.status] ?? '状态未确认'}</strong>
      <p>{new Date(t.observedAt).toLocaleString()} · {t.target ?? t.approvals?.[0]?.targetEnvironment ?? '目标尚无回执或审批确认'}{t.releaseId ? ` · 候选 ${t.releaseId}` : ''}</p>
      <p>{t.nextAction}</p>{t.receipt && <p>副作用：{t.receipt.databaseMayHaveChanged === true ? '数据库可能已改变；' : ''}{t.receipt.productionModified === true || t.receipt.deployed === true ? '曾改变生产应用；' : ''}候选、镜像、备份及尝试材料保留；具体范围见回执。</p>}
      <button onClick={() => props.onRun(t.runId)}>查看来源运行与审计</button><details><summary>技术身份、原始回执与审计</summary><pre>{JSON.stringify(t,null,2)}</pre></details>
    </article>)}</details></dialog>}</div>
}
