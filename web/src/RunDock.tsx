import { useEffect, useState } from 'react'
import { stageLabel, terminalRun } from './runFeedback'
import './run-dock.css'
import { projectApproval } from './projectApproval'

export type LiveApproval = { id:string; toolName:string; capability:string; riskLevel:string; targetEnvironment:string; argumentsJson:string; argumentsSha256:string; expiresAt:string }
export type RunActivity = { id:string; status:string; startedAt:string; lastStep?:{ stepType:string; toolName?:string; createdAt?:string } | null; pendingApproval?:LiveApproval | null; checkedAt:string }

export function RunDock({ active, startedAt, activity, toolLabel, checkedAt, error, approval, approvalSeconds, deciding, stopping, runId, onDecide, onStop, onRetry }: {
  active:boolean; startedAt?:number; activity?:RunActivity; toolLabel?:string; checkedAt?:number; error?:string;
  approval?:LiveApproval; approvalSeconds:number; deciding:boolean; stopping:boolean; runId?:string;
  onDecide:(approved:boolean)=>void; onStop:()=>void; onRetry:()=>void;
}) {
  const [now,setNow]=useState(Date.now())
  const [compact,setCompact]=useState(false)
  useEffect(()=>{ if(!active && !approval) return; setNow(Date.now()); const timer=window.setInterval(()=>setNow(Date.now()),1000); return ()=>window.clearInterval(timer) },[active,approval?.id])
  if(!active && !approval) return null
  const elapsed=Math.max(0,Math.floor((now-(activity ? Date.parse(activity.startedAt) : startedAt ?? now))/1000))
  const age=checkedAt ? Math.max(0,Math.floor((now-checkedAt)/1000)) : undefined
  const stopped=activity?.status==='CANCEL_REQUESTED'
  const ended=activity && terminalRun(activity.status)
  const unknown=Boolean(error) || (age !== undefined && age>10)
  const project=approval ? projectApproval(approval.targetEnvironment) : undefined
  return <aside className={`run-dock ${approval ? 'run-dock--approval' : ''}`} aria-label={approval ? '待审批操作' : '当前任务'}>
    <header><strong>{unknown ? '后台连接中断，任务状态待确认' : approval ? '需要你批准，任务才会继续' : ended ? '后台任务已结束，正在读取结果' : activity?.lastStep?.stepType==='MODEL_CONNECTION_RETRY' ? '模型连接中断，正在重新连接' : stageLabel(activity?.status,activity?.lastStep?.toolName)}</strong>{!approval && <button className="ghost" aria-expanded={!compact} onClick={()=>setCompact(!compact)}>{compact ? '展开' : '收起'}</button>}</header>
    {unknown && <p className="error-text" role="status">暂时无法确认后台状态，请勿重复执行。<button className="ghost" onClick={onRetry}>重新确认</button></p>}
    {approval ? <><div className="run-dock-body"><p role="alert">{unknown ? '连接恢复并核对当前审批后，才能批准。' : approvalSeconds>0 ? `请核对下面的操作。剩余 ${approvalSeconds} 秒` : '审批已过期，请等待任务结果。'}</p><p>工具：<code>{approval.toolName}</code></p><>{project ? <><p>项目：{project.project}</p><p>源码目录：{project.root}</p>{project.file && <p>文件：{project.file}</p>}{project.isolated && <p>在隔离副本中运行；不开放主机数据，网络关闭。</p>}<details><summary>完整审批目标与校验信息</summary><p>{approval.targetEnvironment}</p></details></> : <p>目标：{approval.targetEnvironment}</p>}</><pre>{approval.argumentsJson}</pre><small>参数摘要 {approval.argumentsSha256.slice(0,16)}… · {approval.capability}/{approval.riskLevel}</small></div><footer><button className="danger" disabled={unknown || deciding || approvalSeconds<=0} onClick={()=>onDecide(false)}>拒绝</button><button className="primary" disabled={unknown || deciding || approvalSeconds<=0} onClick={()=>onDecide(true)}>{deciding ? '提交中…' : '批准执行一次'}</button></footer></> : <><div className="run-dock-summary"><span className={unknown ? 'connection-unknown' : 'waiting-pulse'} aria-hidden="true"/><span>{unknown ? '距任务发起' : '已等待'} {Math.floor(elapsed/60)} 分 {elapsed%60} 秒</span>{runId && <button className="danger compact" disabled={stopping || stopped || Boolean(ended)} onClick={onStop}>{stopping || stopped ? '正在停止…' : '停止运行'}</button>}</div>{!compact && <div className="run-dock-body">{!unknown && <p>{age===undefined ? '正在连接后台…' : `后台状态已确认 · ${age} 秒前`}</p>}{toolLabel && !ended && <p>{unknown ? '最后确认的工具' : '当前工具'}：{toolLabel}</p>}<small>{unknown ? '页面仍在尝试重新连接；计时不代表任务仍在执行。连接恢复后会读取实际结果，不会自动重跑工具。' : stopped ? '停止请求已提交；已完成的操作不会撤销。' : ended ? '请等待结果返回，不要重复发起任务。' : '执行期间可能暂时没有输出。可以继续等待，或停止运行；停止不会撤销已经完成的操作。'}</small></div>}</>}
  </aside>
}
