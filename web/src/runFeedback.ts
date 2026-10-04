export function terminalRun(status:string) { return ['COMPLETED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED'].includes(status) }
export function stageLabel(status?:string, tool?:string) {
  if(status==='WAITING_APPROVAL') return '等待你批准操作'
  if(status==='CANCEL_REQUESTED') return '正在停止任务'
  if(status==='TOOL_RUNNING') return tool ? '正在执行工具任务' : '正在执行任务'
  if(status==='THINKING' || status==='RUNNING') return '助手正在处理请求'
  if(status==='OBSERVING') return '正在整理执行结果'
  return '正在启动任务'
}
