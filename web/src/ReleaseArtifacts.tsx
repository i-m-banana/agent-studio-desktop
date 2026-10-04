import { useEffect,useState } from 'react'
import { releaseStatusLabels } from './releaseHistory'
type Artifact={taskId:string;kind:string;identity:string;target:string;status:string;sourceSha256:string;observedBytes:number|string;workflows:string[];retention:string;runId:string}
export function ReleaseArtifacts({onRun}:{onRun:(id:string)=>void}){
  const [items,setItems]=useState<Artifact[]>([]),[offset,setOffset]=useState(0),[error,setError]=useState(''),[busy,setBusy]=useState(false)
  async function load(start:number){setBusy(true);setError('');try{const r=await fetch(`/api/release-artifacts?limit=100&offset=${start}`);if(!r.ok)throw new Error('材料读取失败');const list=await r.json() as Artifact[];setItems(current=>start===0?list:[...current,...list]);setOffset(start+100)}catch(e){setError(String(e))}finally{setBusy(false)}}
  useEffect(()=>{void load(0)},[])
  return <div><p className="hint">只读历史清单，大小来自工具回执，不是服务器实时占用。所有备份保留；这里没有自动清理或重新执行。</p>{items.map(r=><article className="artifact-row" key={r.taskId}><div><strong>{r.kind} · {r.identity}</strong><p>{r.target} · 历史回执：{releaseStatusLabels[r.status] ?? r.status}</p><p className="hint">{r.kind === "备份" ? "数据库回执大小" : "制品回执大小"}：{typeof r.observedBytes==='number'?`${r.observedBytes.toLocaleString()} 字节`:r.observedBytes} · 本页事件关联发布任务 {r.workflows.length} 个</p><p className="hint">{r.retention}</p></div><button className="secondary" onClick={()=>onRun(r.runId)}>来源记录</button></article>)}{!items.length&&!busy&&<p>这一页尚无材料回执。</p>}<button className="secondary" disabled={busy} onClick={()=>void load(offset)}>加载更早的材料</button>{error&&<p role="alert">{error}</p>}</div>
}
