import { backupAgeMinutes, hasVersionOneBaseline, type WorkflowEvidence } from './releaseWorkflow'

export type ProductionStatus = { successful: boolean; exitCode: number; target: string; currentImageId?: string; productionSha256?: string; appHealth?: string; observedAt?: number }
export function publishReadiness(evidence: WorkflowEvidence, production: ProductionStatus | undefined, target: string, now = Date.now(), lastAttemptAt = 0) {
  const identity = /^[0-9a-f]{64}$/
  const fresh = (at?: number) => Boolean(at && at > lastAttemptAt && at <= now && now - at < 5 * 60_000)
  const same = (value?: { target?: string; successful: boolean; exitCode: number }) => value?.target === target && value?.successful === true && value.exitCode === 0
  const candidate = evidence.candidate; const image = evidence.image; const schema = evidence.schema; const backup = evidence.backup
  const age = backupAgeMinutes(backup?.backupId, now)
  const reasons: string[] = []
  if (!same(candidate) || !same(image) || candidate?.releaseId !== image?.releaseId || candidate?.manifestSha256 !== image?.manifestSha256
      || image?.stage !== 'IMAGE_READY' || !image?.imageId?.match(/^sha256:[0-9a-f]{64}$/)) reasons.push('先准备并构建同一候选')
  if (!same(backup) || !backup?.manifestSha256?.match(identity) || age === undefined || age < 0 || age > 30) reasons.push('创建30分钟内的新备份')
  if (!same(schema) || !schema?.schemaComplete || !schema.schemaSha256?.match(identity) || !fresh(schema.observedAt)) reasons.push('重新核查数据库结构（5分钟有效）')
  if (!same(evidence.status) || !hasVersionOneBaseline(evidence.status?.output) || !fresh(evidence.status?.observedAt)) reasons.push('检查数据库版本历史（5分钟有效）')
  if (!same(production) || production?.appHealth !== 'healthy' || !fresh(production?.observedAt)
      || !production.currentImageId?.match(/^sha256:[0-9a-f]{64}$/) || !production.productionSha256?.match(identity)) reasons.push('读取健康的当前生产身份（5分钟有效）')
  if (production?.currentImageId && production.currentImageId === image?.imageId) reasons.push('此镜像已经在线，无需再次上线')
  const args = { releaseId: image?.releaseId, manifestSha256: image?.manifestSha256, imageId: image?.imageId,
    schemaSha256: schema?.schemaSha256, backupId: backup?.backupId, backupManifestSha256: backup?.manifestSha256,
    previousImageId: production?.currentImageId, productionSha256: production?.productionSha256 }
  return { ready: reasons.length === 0, reasons, args }
}
