import { useEffect, useRef, useState } from 'react'
import { readablePreview } from './listTools'

export type ConversationSummary = { id: string; agentVersionId: string; updatedAt: string; preview?: string; messageCount: number; activeRunId?: string }
export function HistoryPanel(props: {
  conversations: ConversationSummary[]; conversationId?: string; busy: boolean;
  onOpen: (id:string)=>void; onNew: ()=>void; onTrash:(ids:string[],restore:boolean)=>Promise<void>; onError:(message:string)=>void
  onSearch:(query:string,offset:number,deleted:boolean)=>Promise<ConversationSummary[]>
}) {
  const [managing,setManaging] = useState(false)
  const [deleted,setDeleted] = useState(false)
  const [query,setQuery] = useState('')
  const [records,setRecords] = useState<ConversationSummary[]>([])
  const [loading,setLoading] = useState(false)
  const [hasMore,setHasMore] = useState(false)
  const request = useRef(0)
  const [selected,setSelected] = useState<string[]>([])
  const [saving,setSaving] = useState(false)
  const [error,setError] = useState('')
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { if (managing) dialog.current?.showModal() },[managing])
  async function load(offset = 0) {
    const token = ++request.current; setLoading(true); setError('')
    try {
      const page = await props.onSearch(query,offset,deleted)
      if (token !== request.current) return
      setRecords(current => offset ? [...current,...page.filter(item => !current.some(old => old.id === item.id))] : page)
      setHasMore(page.length === 50)
    } catch(e) { if (token === request.current) { setError(String(e)); setHasMore(false) } }
    finally { if (token === request.current) setLoading(false) }
  }
  useEffect(() => {
    request.current++; setSelected([]); setRecords([]); setHasMore(false)
    if (!managing) return
    setLoading(true)
    const timer = window.setTimeout(() => void load(), query ? 250 : 0)
    return () => { window.clearTimeout(timer); request.current++ }
  },[managing,query,deleted])
  async function changeHistory() {
    setSaving(true); setError('')
    try {
      await props.onTrash(selected,deleted); setSelected([]); await load()
    } catch (e) { setError(String(e)); props.onError(String(e)) }
    finally { setSaving(false) }
  }
  return <div className="history-compact" aria-label="历史会话">
    <select aria-label="历史会话" value={props.conversationId ?? ''} disabled={props.busy} onChange={e => e.target.value ? props.onOpen(e.target.value) : props.onNew()}>
      <option value="">历史会话</option>{props.conversations.map(c => <option key={c.id} value={c.id}>{new Date(c.updatedAt).toLocaleString()} · {readablePreview(c.preview).slice(0,32)}{c.activeRunId ? ' · 进行中' : ''}</option>)}</select>
    <button className="ghost" disabled={props.busy} onClick={props.onNew}>新会话</button>
    <button className="ghost" disabled={props.busy} onClick={() => { setManaging(true); setDeleted(false); setQuery(''); setSelected([]); setError('') }}>管理</button>
    {managing && <dialog className="history-dialog" ref={dialog} onClose={() => setManaging(false)} onCancel={e => { if (saving) e.preventDefault() }}>
      <header><h2>管理历史会话</h2><button className="ghost" autoFocus disabled={saving} aria-label="关闭历史管理" onClick={() => dialog.current?.close()}>关闭</button></header>
      <p>删除后移到“已删除”，可以恢复。发布执行记录和服务器上的文件不会被删除。</p>
      {error && <p role="alert">{error}</p>}
      <input type="search" aria-label="搜索历史会话" placeholder="搜索会话正文或编号" maxLength={200} value={query} disabled={saving} onChange={e => setQuery(e.target.value)} />
      <div className="history-actions"><button className="secondary" aria-pressed={!deleted} disabled={saving} onClick={() => setDeleted(false)}>会话清单</button>
        <button className="secondary" aria-pressed={deleted} disabled={saving} onClick={() => setDeleted(true)}>已删除</button>
        <button className="secondary" disabled={saving || loading} onClick={() => setSelected(records.filter(c => !c.activeRunId).slice(0,200).map(c => c.id))}>选中当前列表</button>
        <button className="ghost" disabled={saving} onClick={() => setSelected([])}>取消选择</button>
        <button className={deleted ? 'secondary' : 'danger'} disabled={saving || loading || !selected.length} onClick={() => void changeHistory()}>{saving ? '处理中…' : `${deleted ? '恢复' : '删除'}选中的 ${selected.length} 条`}</button></div>
      <p>{deleted ? '已删除' : '会话清单'} · {query ? '匹配结果' : '当前显示'} {records.length} 条{loading ? ' · 读取中…' : ''}</p>
      <div className="history-list">{records.map(c => <div className="history-item" key={c.id}><label><input type="checkbox" disabled={saving || loading || Boolean(c.activeRunId) || (!selected.includes(c.id) && selected.length >= 200)} checked={selected.includes(c.id)} onChange={e => setSelected(e.target.checked ? [...selected,c.id] : selected.filter(id => id !== c.id))}/><span>{new Date(c.updatedAt).toLocaleString()} · {readablePreview(c.preview)}<small>{c.messageCount} 条消息{c.activeRunId ? ' · 执行中，暂不能删除' : ''}</small></span></label>{!deleted && <button className="ghost" disabled={saving || props.busy || loading} onClick={() => { dialog.current?.close(); props.onOpen(c.id) }}>打开</button>}</div>)}{!records.length && !loading && !error && <p>{query ? '没有匹配的会话。' : '这里还没有会话。'}</p>}</div>
      {hasMore && <button className="secondary" disabled={saving || loading} onClick={() => void load(records.length)}>加载更多会话</button>}
      {error && <button className="secondary" disabled={saving || loading} onClick={() => void load()}>重新读取</button>}
</dialog>}</div>
}
