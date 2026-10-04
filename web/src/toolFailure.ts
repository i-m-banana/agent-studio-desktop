/** Explanations never infer side effects or authorize retries. */
export function explainToolFailure(r:Record<string,unknown>):string|undefined{
  if(r.successful===true&&(r.outputTruncated!==true||r.task==='MYSQL_INTEGRATION'&&r.allRequiredSuitesPassed===true))return undefined
  const log=`${typeof r.output==='string'?r.output:''}\n${typeof r.error==='string'?r.error:''}`
  const markers=[...log.matchAll(/AGENTSTUDIO_STAGE=([A-Z_]+)/g)];const stage=typeof r.stage==='string'?r.stage:markers.at(-1)?.[1]
  let reason=r.outputTruncated===true?'日志被截断，无法用本次日志确认完整结果。':'请核对本阶段原始记录，当前结果不能视为完成。'
  if(r.failureCategory==='DEPENDENCY_CACHE_MISSING'||/offline mode|has not been downloaded/i.test(log))reason='隔离镜像缺少离线依赖；修复可信缓存后重新请求验证。测试尚未通过。'
  else if(/\$'\\r'|\r: command not found|bad interpreter|unexpected.*\r/i.test(log))reason='脚本换行格式不适合 Linux；先修复平台脚本并核对线上现状，不能直接重试发布。'
  else if(/connection reset|session.*closed|broken pipe|channel.*closed/i.test(log))reason='远程连接中断；远程操作可能仍执行或已经完成，先核查线上身份、版本历史和健康。'
  else if(/timed?\s*out|timeout|超时/i.test(log))reason='等待已超时；超时不能证明远程操作未发生，先核查该阶段状态再决定下一动作。'
  else if(/could not resolve|download.*fail|failed.*download/i.test(log))reason='构建依赖下载失败；候选可能已保留，核对失败阶段后处理缓存，不重复成功的前置步骤。'
  return `${stage?`最后记录阶段：${stage}。`:''}${reason}`
}
