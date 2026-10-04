import { useEffect, useState } from 'react'

type Preview = { id: string; state: string; sourceSha256: string; url?: string; expiresAt: number; message: string }
export function previewUrl(value?: string) {
  if (!value) return undefined
  try { const u = new URL(value); return u.protocol === 'http:' && u.hostname === '127.0.0.2' && /^\d+$/.test(u.port) && Number(u.port) > 0 && !u.username && !u.password && u.pathname === '/' ? u.origin : undefined } catch { return undefined }
}
export function ProjectPreview({ projectId, busy, enabled, onStart }: { projectId: string; busy: boolean; enabled: boolean; onStart: () => void }) {
  const [items, setItems] = useState<Preview[]>([])
  const [error, setError] = useState('')
  const [stopping, setStopping] = useState(false)
  useEffect(() => {
    let disposed = false
    setItems([]); setError('')
    const load = async () => {
      try { const r = await fetch(`/api/local-previews?projectId=${encodeURIComponent(projectId)}`); if (!r.ok) throw new Error('预览状态读取失败'); const data = await r.json(); if (!disposed) { setItems(data); setError('') } }
      catch (e) { if (!disposed) setError(String(e)) }
    }
    void load(); const timer = setInterval(() => void load(), 5000)
    return () => { disposed = true; clearInterval(timer) }
  }, [projectId])
  const active = items.find(p => !['STOPPED', 'FAILED'].includes(p.state))
  async function stop(id: string) {
    setStopping(true)
    try { const r = await fetch(`/api/local-previews/${id}/stop`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }); if (!r.ok) throw new Error('停止未确认，请保留记录并重试'); setItems(items.map(p => p.id === id ? { ...p, state: 'STOPPED', url: undefined } : p)) }
    catch (e) { setError(String(e)) } finally { setStopping(false) }
  }
  return <section className="session-validation"><h3>网站隔离预览</h3>
    <p className="hint">仅支持当前网站。审批后在断网容器中测试打包，使用 H2 合成数据；不连接真实数据库，不读取真实附件。按项目设置到期，最长 30 分钟，不替代 MySQL 集成测试。</p>
    <button className="secondary" disabled={busy || !enabled || Boolean(active)} onClick={onStart}>请求启动预览</button>
    {!enabled && <p className="hint">请在助手管理勾选“启动受审网站隔离预览”并发布新版本。</p>}
    {active && <><p>状态：{({ READY: '已就绪', STARTING: '启动中', CLEANUP_REQUIRED: '待回收，不能再次启动' } as Record<string, string>)[active.state] ?? active.state} · 到期：{new Date(active.expiresAt).toLocaleTimeString()}</p>
      <p className="hint">{active.message}</p><p className="hint" style={{ overflowWrap: 'anywhere' }}>源码快照：{active.sourceSha256}</p>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {active.state === 'READY' && previewUrl(active.url) && <a className="button secondary" href={previewUrl(active.url)} target="_blank" rel="noopener noreferrer">打开预览</a>}
        <button className="ghost" disabled={stopping} onClick={() => void stop(active.id)}>停止并清理本次预览</button>
      </div></>}
    {!active && items.length > 0 && (items[0].state === 'FAILED' ? <details><summary>最近预览启动失败，查看原因</summary><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',maxHeight:240,overflow:'auto'}}>{items[0].message}</pre><p className="hint">源码 {items[0].sourceSha256.slice(0,8)} · 可核对原因后重新请求预览。</p></details> : <p className="hint">最近预览已停止；本地源码未修改。</p>)}
    {error && <p role="alert">{error}</p>}
  </section>
}
