export const baselineFields = ['releaseId', 'manifestSha256', 'imageId', 'schemaSha256', 'backupId', 'backupManifestSha256'] as const
export function mergeBaselineEvidence(input: string, evidence: Partial<Record<typeof baselineFields[number], string>>) {
  try {
    const current = JSON.parse(input)
    if (!current || Array.isArray(current) || typeof current !== 'object') return input
    return JSON.stringify({ ...current, ...evidence }, null, 2)
  } catch { return input } // don't overwrite an in-progress user edit
}
export function databaseBaselineRequest(input: string) {
  const value = JSON.parse(input) as Record<string, unknown>
  if (!value || Array.isArray(value) || typeof value !== 'object' || Object.keys(value).length !== baselineFields.length) throw new Error('请填写六项绑定身份，不得添加 SQL 或选项')
  for (const key of baselineFields) {
    const pattern = key === 'imageId' ? /^sha256:[0-9a-f]{64}$/ : key.endsWith('Id') ? /^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$/ : /^[0-9a-f]{64}$/
    if (typeof value[key] !== 'string' || !pattern.test(value[key])) throw new Error(`请填写完整的 ${key}`)
  }
  return `请只使用 adopt_remote_database_baseline 工具，参数严格为 ${JSON.stringify(value)}。仅登记版本1基线，不执行迁移或发布，不得提供SQL、路径、命令、版本或额外选项。`
}
