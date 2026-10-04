import { receiptOutcome } from './toolResults'

export type WorkflowEvidence = {
  candidate?: { successful: boolean; exitCode: number; releaseId?: string; manifestSha256?: string; target?: string }
  image?: { successful: boolean; exitCode: number; releaseId?: string; manifestSha256?: string; imageId?: string; target?: string; stage?: string; output?: string }
  backup?: { successful: boolean; exitCode: number; backupId?: string; manifestSha256?: string; target?: string }
  schema?: { successful: boolean; exitCode: number; schemaComplete?: boolean; schemaSha256?: string; target?: string; observedAt?: number }
  baseline?: { successful: boolean; exitCode: number; baselineRegistered?: boolean; target?: string; observedAt?: number }
  status?: { successful: boolean; exitCode: number; target?: string; observedAt?: number; output?: string }
  health?: { successful: boolean; exitCode: number; target?: string; observedAt?: number }
}

export function backupAgeMinutes(backupId?: string, now = Date.now()): number | undefined {
  const match = backupId?.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z-[0-9a-f]{8}$/)
  if (!match) return undefined
  const timestamp = Date.parse(`${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:${match[6]}Z`)
  return Number.isFinite(timestamp) ? (now - timestamp) / 60_000 : undefined
}

export function hasVersionOneBaseline(output?: string): boolean {
  if (!output) return false
  return output.split(/\r?\n/).some((line) => {
    try {
      const row: unknown = JSON.parse(line.trim())
      return Array.isArray(row) && row.length === 3 && String(row[0]) === '1'
        && row[1] === 'BASELINE' && (row[2] === 1 || row[2] === true)
    } catch { return false }
  })
}

export function workflowReadiness(evidence: WorkflowEvidence, target: string, now = Date.now()) {
  const same = (value?: { target?: string }) => Boolean(value?.target && value.target === target)
  const candidate = Boolean(evidence.candidate?.successful && evidence.candidate.exitCode === 0 && same(evidence.candidate)
    && evidence.candidate.releaseId && evidence.candidate.manifestSha256?.match(/^[0-9a-f]{64}$/))
  const image = Boolean(evidence.image?.successful && evidence.image.exitCode === 0 && same(evidence.image)
    && evidence.image.stage === 'IMAGE_READY' && evidence.image.imageId?.match(/^sha256:[0-9a-f]{64}$/)
    && evidence.image.output?.includes('AGENTSTUDIO_STAGE=RUNTIME_SMOKE')
    && evidence.image.releaseId === evidence.candidate?.releaseId
    && evidence.image.manifestSha256 === evidence.candidate?.manifestSha256)
  const age = backupAgeMinutes(evidence.backup?.backupId, now)
  const backup = Boolean(evidence.backup?.successful && evidence.backup.exitCode === 0 && same(evidence.backup)
    && evidence.backup.manifestSha256?.match(/^[0-9a-f]{64}$/) && age !== undefined && age >= 0 && age <= 30)
  const schema = Boolean(evidence.schema?.successful && evidence.schema.exitCode === 0 && same(evidence.schema)
    && evidence.schema.schemaComplete && evidence.schema.schemaSha256?.match(/^[0-9a-f]{64}$/))
  const baseline = Boolean(evidence.baseline?.successful && evidence.baseline.exitCode === 0
    && evidence.baseline.baselineRegistered === true && same(evidence.baseline))
  const afterBaseline = (value?: { observedAt?: number }) => Boolean(value?.observedAt && evidence.baseline?.observedAt
    && value.observedAt > evidence.baseline.observedAt)
  const status = Boolean(evidence.status?.successful && evidence.status.exitCode === 0 && same(evidence.status)
    && afterBaseline(evidence.status) && hasVersionOneBaseline(evidence.status.output))
  const health = Boolean(evidence.health?.successful && evidence.health.exitCode === 0 && same(evidence.health) && afterBaseline(evidence.health))
  return { candidate, image, backup, schema, baseline, status, health, canRegister: candidate && image && backup && schema && !baseline,
    backupAgeMinutes: age }
}

export function fixedToolReceipt(message: string): Record<string, unknown> | undefined {
  if (!message.startsWith('固定任务工具已执行；以下为原始工具结果')) return undefined
  const match = message.match(/```json\s*([\s\S]*?)\s*```/)
  if (!match) return undefined
  try {
    const parsed: unknown = JSON.parse(match[1])
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && typeof (parsed as Record<string, unknown>).successful === 'boolean')
      return parsed as Record<string, unknown>
  } catch { /* preserve the original message when malformed */ }
  return undefined
}

export function isBaselineRegistrationReceipt(receipt: Record<string, unknown>): boolean {
  return typeof receipt.task !== 'string' && 'baselineVersion' in receipt && 'baselineRegistered' in receipt
}

export function fixedToolSucceeded(receipt: Record<string, unknown>): boolean {
  return receiptOutcome(receipt) === 'success'
}
