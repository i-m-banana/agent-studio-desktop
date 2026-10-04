import { useState, type FormEvent } from 'react'
import { ProjectRuntimeEditor } from './ProjectRuntimeEditor'

export type LocalProject = { id: string; name: string; sourceRoot: string; writableDirectories: string[]; protectedDirectories: string[]; revision: number; archived: boolean; protectionConfirmed: boolean }
export type ProjectStatus = { project: LocalProject; status: string; message: string }
export type SandboxStatus = { configured: boolean; message: string }
export type ProjectBinding = { projectId: string; revision: number; workspaceIdentity: string }
const defaults = { name: '', sourceRoot: '', writable: 'src/main\nsrc/test', protected: 'data\nmysql-data\nuploads\nbackups\nlogs\n.secrets', protectionConfirmed: false }

export function LocalProjects({ projects, roots, sandbox, onChanged }: { projects: ProjectStatus[]; roots: string[]; sandbox?: SandboxStatus; onChanged: () => Promise<void> }) {
  const [form, setForm] = useState(defaults)
  const [editing, setEditing] = useState<LocalProject>()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [showArchived, setShowArchived] = useState(false)
  async function request(path: string, body?: unknown, method = 'POST') {
    setBusy(true); setError('')
    try {
      const response = await fetch(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) })
      if (!response.ok) { const data = await response.json(); throw new Error(data.message ?? '保存失败') }
      await onChanged()
      return true
    } catch (e) { setError(e instanceof Error ? e.message : '操作失败'); return false }
    finally { setBusy(false) }
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    const lines = (text: string) => text.split('\n').map(s => s.trim()).filter(Boolean)
    const saved = await request(`/api/local-projects${editing ? `/${editing.id}` : ''}`, { name: form.name, sourceRoot: form.sourceRoot, writableDirectories: lines(form.writable), protectedDirectories: lines(form.protected), expectedRevision: editing?.revision, protectionConfirmed: form.protectionConfirmed }, editing ? 'PUT' : 'POST')
    if (saved) { setEditing(undefined); setForm(defaults); setOpen(false) }
  }
  function edit(project: LocalProject) {
    setEditing(project); setForm({ name: project.name, sourceRoot: project.sourceRoot, writable: project.writableDirectories.join('\n'), protected: project.protectedDirectories.join('\n'), protectionConfirmed: project.protectionConfirmed }); setError(''); setOpen(true)
  }
  const visibleProjects = projects.filter(p => showArchived || !p.project.archived)
  return <div className="local-projects">
    <div className="project-list-toolbar">
      <div><h2>本地项目 <span>{projects.filter(p => !p.project.archived).length}</span></h2><p>归档保留源码与会话记录。</p></div>
      {!open && <div className="project-list-actions"><label className="project-checkbox"><input type="checkbox" checked={showArchived} onChange={e => setShowArchived(e.target.checked)} /><span>显示归档项目</span></label><button className="primary" disabled={busy} onClick={() => { setEditing(undefined); setForm(defaults); setError(''); setOpen(true) }}>登记项目</button></div>}
    </div>
    {error && <p className="run-error" role="alert">{error}</p>}
    {open ? <form className="panel project-editor" onSubmit={save} aria-label={editing ? '编辑本地项目' : '登记本地项目'}>
      <header className="project-editor-head"><div><h2>{editing ? '编辑本地项目' : '登记本地项目'}</h2><p>仅授权选定项目，不会开放上级目录。</p></div><button type="button" className="ghost" disabled={busy} onClick={() => { setOpen(false); setError('') }}>返回列表</button></header>
      <div className="project-basic-fields">
        <label>项目名称<input required maxLength={120} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
        <label>授权源码目录<select required value={form.sourceRoot} onChange={e => setForm({ ...form, sourceRoot: e.target.value, protectionConfirmed: false })}><option value="">选择明确授权的项目</option>{roots.map(root => <option key={root} value={root}>{root}</option>)}</select></label>
      </div>
      {!roots.length && <p className="run-error">尚未配置服务器允许清单。请由本机管理者授权具体项目目录。</p>}
      <div className="project-path-fields">
        <label>允许修改的路径<span className="project-field-help">源码或测试目录，也可填写已有文件；每行一个相对路径。</span><textarea required rows={6} spellCheck={false} value={form.writable} onChange={e => setForm({ ...form, writable: e.target.value })} /></label>
        <label>保护的数据目录<span className="project-field-help">填写真实数据、上传文件及备份目录；每行一个相对路径。</span><textarea rows={6} spellCheck={false} value={form.protected} onChange={e => setForm({ ...form, protected: e.target.value, protectionConfirmed: false })} /><small>默认数据目录始终受保护，可在此补充其他目录。</small></label>
      </div>
      <label className="project-checkbox project-confirm"><input type="checkbox" checked={form.protectionConfirmed} onChange={e => setForm({ ...form, protectionConfirmed: e.target.checked })} /><span><strong>已核对真实数据保护范围</strong><small>确认后才允许编码与测试；测试请使用专用数据。</small></span></label>
      <footer className="project-editor-footer"><p>{editing ? '保存后，继续编码需新建会话。历史会话仍可查看。' : '登记后，在新会话中选择此项目即可使用。'}运行中的项目不能修改。</p><div><button type="button" className="ghost" disabled={busy} onClick={() => { setOpen(false); setError('') }}>取消</button><button className="primary" disabled={busy || !roots.length}>{busy ? '保存中…' : '保存项目'}</button></div></footer>
    </form> : <div className="project-list">
      {visibleProjects.length === 0 && <div className="panel project-empty"><h3>{showArchived ? '暂无项目' : '暂无使用中的项目'}</h3><p>登记一个已授权的源码目录，供助手在会话中使用。</p></div>}
      {visibleProjects.map(({ project, status, message }) => <article className="panel project-card" key={project.id}>
        <div className="project-card-heading"><h3>{project.name}</h3><span className={`project-status project-status--${status.toLowerCase()}`}>{status === 'READY' ? '可用' : status === 'ARCHIVED' ? '已归档' : status === 'NEEDS_PROTECTION' ? '待确认保护范围' : '目录不可用'}</span></div>
        <p className="project-source-path">{project.sourceRoot}</p>
        <dl className="project-scope"><div><dt>允许修改</dt><dd>{project.writableDirectories.join(' · ') || '未设置'}</dd></div><div><dt>数据保护</dt><dd>{project.protectedDirectories.length} 个目录</dd></div></dl>
        <footer className="project-card-footer"><p>{message}</p><div><button className="ghost" disabled={busy} onClick={() => void request(`/api/local-projects/${project.id}/check`)}>检查目录</button><button className="ghost" disabled={busy} onClick={() => void request(`/api/local-projects/${project.id}/${project.archived ? 'restore' : 'archive'}`)}>{project.archived ? '恢复' : '归档'}</button><button className="secondary" disabled={busy} onClick={() => edit(project)}>编辑项目</button></div></footer>
        <ProjectRuntimeEditor project={project} onChanged={onChanged}/>
      </article>)}
    </div>}
    <p className="project-sandbox-note" role="status"><span aria-hidden="true">◇</span>{sandbox?.message ?? '正在读取隔离验证配置…'} MySQL 模式另见预览与测试设置。</p>
  </div>
}
