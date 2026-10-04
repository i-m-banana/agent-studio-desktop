import { useState } from 'react'
import { ReleaseArtifacts } from './ReleaseArtifacts'
import { type ReleaseTask, releaseStatusLabels } from './releaseHistory'
const names:Record<string,string>={prepare_release_candidate:'准备候选',build_release_candidate_image:'构建镜像',prepare_remote_deployment_backup:'创建备份',inspect_remote_deployment:'只读部署核查',adopt_remote_database_baseline:'登记基线',verify_remote_deployment_backup_restore:'恢复材料演练',publish_remote_release:'受审上线'}
export function ReleaseRecords({ tasks, target, candidateId, busy, onCandidate, onRun, onRefresh, onMore }: {
  tasks:ReleaseTask[]; target:string; candidateId?:string; busy:boolean; onCandidate:(id:string)=>void;
  onRun:(id:string)=>void; onRefresh:()=>Promise<void>; onMore:()=>Promise<void>;
}) {
  const [loading,setLoading]=useState(false)
  const [artifacts,setArtifacts]=useState(false)
  const [error,setError]=useState('')
  async function load(more:boolean) { setLoading(true);setError('');try { await (more ? onMore() : onRefresh()) } catch(e) { setError(String(e)) } finally { setLoading(false) } }
  const candidates=tasks.filter(t=>t.toolName==='prepare_release_candidate' && t.status==='SUCCEEDED' && t.target===target)
  return <div className="panel release-records"><div className="section-head"><h2>发布记录</h2><button className="ghost" disabled={loading} onClick={()=>void load(false)}>刷新</button></div><details onToggle={e=>setArtifacts(e.currentTarget.open)}><summary>候选、镜像与备份清单</summary>{artifacts && <ReleaseArtifacts onRun={onRun}/>}</details><p className="hint">查看各次发布的候选、镜像、备份、结果和审计。这里不会重新执行操作。</p><label className="field">远程工作台使用的候选材料<select value={candidateId ?? ''} disabled={busy} onChange={e=>onCandidate(e.target.value)}><option value="">仅使用当前会话的材料</option>{candidates.map(t=><option key={t.id} value={t.id}>{new Date(t.observedAt).toLocaleString()} · {t.releaseId}</option>)}</select></label><p className="hint">更换材料只改变远程工作台的来源选择，不会构建、上线或复用旧审批。</p>{error && <p role="alert" className="error-text">{error}</p>}{tasks.length===0 && <p>尚无发布记录。</p>}<div className="release-record-list">{tasks.map(t=><details className="history-stage" key={t.id}><summary><strong>{names[t.toolName] ?? t.toolName} · {releaseStatusLabels[t.status] ?? '状态未确认'}</strong><small>{new Date(t.observedAt).toLocaleString()}</small></summary><p>{new Date(t.observedAt).toLocaleString()} · {t.target ?? t.approvals?.[0]?.targetEnvironment ?? '目标尚未确认'}{t.releaseId ? ` · 候选 ${t.releaseId}` : ''}</p><p>{t.nextAction}</p>{t.receipt && <p>副作用：{t.receipt.databaseMayHaveChanged===true ? '数据库可能已改变；' : ''}{t.receipt.productionModified===true || t.receipt.deployed===true ? '曾改变生产应用；' : ''}具体范围见原始回执。</p>}<button className="secondary" onClick={()=>onRun(t.runId)}>查看来源运行与审计</button><details><summary>技术身份、原始回执与审计</summary><pre>{JSON.stringify(t,null,2)}</pre></details></details>)}</div><button className="secondary" disabled={loading} onClick={()=>void load(true)}>{loading ? '读取中…' : '加载更早的发布记录'}</button></div>
}
