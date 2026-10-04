export type ReleaseTask = {
  id: string; runId: string; conversationId: string; agentVersionId: string; toolName: string
  status: string; createdAt: string; observedAt: string; target?: string; releaseId?: string
  receipt?: Record<string, unknown>; rawReceipt?: string; nextAction: string
  sourceStep: { id: string; inputJson?: string }; auditEvents: unknown[]
  approvals?: { targetEnvironment: string }[]
}
export function knownBaseline(tasks: ReleaseTask[], target: string): boolean {
  return tasks.some(t => t.target === target && t.status === 'SUCCEEDED' &&
    (t.toolName === 'adopt_remote_database_baseline' && t.receipt?.baselineRegistered === true
      || t.toolName === 'inspect_remote_deployment' && t.receipt?.task === 'DATABASE_BASELINE_STATUS'
        && typeof t.receipt.output === 'string' && t.receipt.output.split(/\r?\n/).some(line => {
          try { const row=JSON.parse(line.trim()); return Array.isArray(row) && row.length===3 && String(row[0])==='1'
            && row[1]==='BASELINE' && (row[2]===1 || row[2]===true) } catch { return false }
        })))
}
export function historyEvidence(tasks: ReleaseTask[], conversationId?: string, candidateId?: string, target?: string, projectId?: string) {
  const candidate = tasks.find(t => t.id === candidateId && t.status === 'SUCCEEDED'
    && t.toolName === 'prepare_release_candidate' && t.target === target && (!projectId || t.receipt?.projectId === projectId))
  return tasks.filter(t => {
    if (t.target && target && t.target !== target) return false
    if (projectId && t.toolName === 'prepare_release_candidate' && t.receipt?.projectId !== projectId) return false
    if (candidate && t.toolName === 'prepare_release_candidate') return t.id === candidate.id
    if (candidate && t.toolName === 'build_release_candidate_image') return t.releaseId === candidate.releaseId
      && (t.receipt?.manifestSha256 ?? parseInput(t.sourceStep.inputJson)?.manifestSha256) === candidate.receipt?.manifestSha256
      && (!t.target || t.target === target)
    if (candidateId && !candidate && ['prepare_release_candidate','build_release_candidate_image'].includes(t.toolName)) return false
    return Boolean(conversationId && t.conversationId === conversationId)
  }).sort((a,b) => Date.parse(a.observedAt) - Date.parse(b.observedAt)).map(t => ({
    id: t.id, stepNumber: 0, stepType: 'TOOL_RESULT', status: t.status, toolName: t.toolName,
    outputText: ['SUCCEEDED','DEPLOYED','RESTORED','FAILED','MANUAL_INTERVENTION'].includes(t.status)
      && t.receipt ? JSON.stringify(t.receipt) : JSON.stringify({ successful: false, exitCode: -1,
        task: t.receipt?.task ?? parseTask(t.sourceStep.inputJson), target: t.target, historyStatus: t.status }),
    observedAt: Date.parse(t.observedAt),
  }))
}
function parseInput(input?: string): Record<string,unknown> | undefined {
  try { return input ? JSON.parse(input) : undefined } catch { return undefined }
}
function parseTask(input?: string) { return parseInput(input)?.task }
export const releaseStatusLabels: Record<string,string> = {
  SUCCEEDED: '阶段已成功', DEPLOYED: '已上线并验证健康', RESTORED: '上线失败，旧应用已恢复',
  FAILED: '阶段失败', MANUAL_INTERVENTION: '需要人工介入', UNKNOWN: '状态未确认',
  NOT_EXECUTED: '未执行', WAITING: '等待原运行', RUNNING: '原运行执行中',
  INCOMPLETE_EVIDENCE: '原回执成功，当前验证证据不足',
}
