export function matchesText(query: string, ...values: (string | undefined)[]) {
  const haystack = values.filter(Boolean).join(' ').toLocaleLowerCase()
  return query.trim().toLocaleLowerCase().split(/\s+/).every(word => haystack.includes(word))
}

export function readablePreview(content?: string) {
  if (!content) return '未命名会话'
  const labels: Record<string, string> = { prepare_release_candidate: '准备发布候选', build_release_candidate_image: '构建候选镜像', prepare_remote_deployment_backup: '创建发布备份', inspect_remote_deployment: '检查部署状态', adopt_remote_database_baseline: '登记数据库基线', verify_remote_deployment_backup_restore: '检查备份恢复材料', publish_remote_release: '发布到网站', run_remote_workspace_task: '运行远程固定任务', list_remote_directory: '浏览远程目录', read_remote_file: '阅读远程文件' }
  const tool = Object.keys(labels).find(name => content.startsWith(`请只使用 ${name} 工具`))
  return tool ? labels[tool] : content.replace(/\s+/g, ' ').trim()
}

export function conversationMarkdown(messages: { role: string; content: string; createdAt?: string }[], versionId: string, conversationId?: string) {
  return `# 会话记录\n\n会话：${conversationId ?? '当前会话'}\n助手版本：${versionId}\n\n` + messages.map(message => `## ${message.role === 'user' ? '我' : '助手'}${message.createdAt ? ` · ${message.createdAt}` : ''}\n\n${message.content}`).join('\n\n---\n\n') + '\n'
}

export function downloadText(name: string, text: string, type = 'text/plain;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const link = document.createElement('a')
  link.href = url; link.download = name; document.body.append(link); link.click(); link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function followingLatest(scrollTop: number, clientHeight: number, scrollHeight: number) {
  return scrollHeight - scrollTop - clientHeight < 80
}

export function nextFileLine(startLine: number, endLine: number, totalLines: number) {
  return endLine >= startLine && endLine < totalLines ? endLine + 1 : undefined
}
