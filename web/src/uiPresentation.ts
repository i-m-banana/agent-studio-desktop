export function statusLabel(status: string) {
  return ({ COMPLETED: '已结束', FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '已超时', INTERRUPTED: '已中断', RUNNING: '运行中', WAITING_APPROVAL: '等待审批', WAITING: '等待中', READY: '检查通过', WARNING: '需注意', DEGRADED: '部分项目需处理', NOT_READY: '有项目需处理', NOT_CONFIGURED: '未配置', CHECKING: '检查中', DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用', PROCESSING: '处理中', INDEXED: '已索引', CONNECTED: '已连接', SUCCESS: '成功' } as Record<string, string>)[status] ?? status
}

export function shouldSubmitOnEnter(event: { key: string; shiftKey: boolean; isComposing: boolean; keyCode?: number }) {
  return event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229
}

export function runConclusion(run: { status: string; errorMessage?: string; steps: { stepType: string; status: string; outputText?: string }[] }) {
  if (run.errorMessage) return run.errorMessage
  const failed = run.steps.some(step => step.status === 'FAILED' || (() => {
    if (step.stepType !== 'TOOL_RESULT' || !step.outputText) return false
    try { const result = JSON.parse(step.outputText); return result.successful === false || (typeof result.exitCode === 'number' && result.exitCode !== 0) } catch { return false }
  })())
  if (failed) return '有操作未成功。展开运行步骤查看失败原因。'
  return run.status === 'COMPLETED' ? '会话运行已结束；具体操作结果以工具回执为准。' : statusLabel(run.status)
}
