import { describe, expect, it } from 'vitest'
import { backupAgeMinutes, fixedToolReceipt, fixedToolSucceeded, hasVersionOneBaseline, isBaselineRegistrationReceipt, workflowReadiness } from './releaseWorkflow'

const target = 'root@example:22/root/opt/old-things'
const candidate = { successful: true, exitCode: 0, target, releaseId: '20260929T141749Z-2d71736c', manifestSha256: 'a'.repeat(64) }
const image = { successful: true, exitCode: 0, target, releaseId: candidate.releaseId, manifestSha256: candidate.manifestSha256,
  imageId: `sha256:${'b'.repeat(64)}`, stage: 'IMAGE_READY', output: 'AGENTSTUDIO_STAGE=RUNTIME_SMOKE' }
const backup = { successful: true, exitCode: 0, target, backupId: '20260930T143429Z-ffba9e8c', manifestSha256: 'c'.repeat(64) }
const schema = { successful: true, exitCode: 0, target, schemaComplete: true, schemaSha256: 'd'.repeat(64) }
const now = Date.parse('2026-09-30T14:40:00Z')

describe('release workflow readiness', () => {
  it('shows a complete read-only schema result as successful even though it did not register a baseline', () => {
    const receipt = { task: 'DATABASE_SCHEMA', successful: true, exitCode: 0, schemaComplete: true,
      outputTruncated: false, databaseModified: false, baselineRegistered: false }
    expect(fixedToolSucceeded(receipt)).toBe(true)
    expect(isBaselineRegistrationReceipt(receipt)).toBe(false)
    expect(fixedToolSucceeded({ ...receipt, schemaComplete: false })).toBe(false)
    expect(fixedToolSucceeded({ ...receipt, outputTruncated: true })).toBe(false)
    expect(fixedToolSucceeded({ ...receipt, successful: false, exitCode: 1 })).toBe(false)
  })

  it('identifies baseline registration separately from image results and requires confirmed registration', () => {
    const receipt = { releaseId: candidate.releaseId, imageId: image.imageId, baselineVersion: '1',
      baselineRegistered: true, successful: true, exitCode: 0 }
    expect(isBaselineRegistrationReceipt(receipt)).toBe(true)
    expect(isBaselineRegistrationReceipt(image)).toBe(false)
    expect(fixedToolSucceeded(image)).toBe(true)
    expect(fixedToolSucceeded(receipt)).toBe(true)
    expect(fixedToolSucceeded({ ...receipt, baselineRegistered: false })).toBe(false)
    expect(fixedToolSucceeded({ ...receipt, baselineRegistered: null })).toBe(false)
    expect(fixedToolSucceeded({ ...receipt, exitCode: 1 })).toBe(false)
  })

  it('requires all four bound, fresh inputs before offering baseline registration', () => {
    expect(workflowReadiness({ candidate, image, backup, schema }, target, now).canRegister).toBe(true)
    expect(workflowReadiness({ candidate, image, backup }, target, now).canRegister).toBe(false)
    expect(workflowReadiness({ candidate: { ...candidate, exitCode: 1 }, image, backup, schema }, target, now).canRegister).toBe(false)
    expect(workflowReadiness({ candidate, image: { ...image, output: '' }, backup, schema }, target, now).canRegister).toBe(false)
    expect(workflowReadiness({ candidate, image, backup: { ...backup, target: 'other' }, schema }, target, now).canRegister).toBe(false)
    expect(workflowReadiness({ candidate, image, backup, schema }, target, now + 31 * 60_000).canRegister).toBe(false)
  })

  it('does not count a completed chat or an unsuccessful baseline as registration', () => {
    const evidence = { candidate, image, backup, schema, baseline: { successful: false, exitCode: 1, target, baselineRegistered: false } }
    expect(workflowReadiness(evidence, target, now).baseline).toBe(false)
    expect(workflowReadiness(evidence, target, now).canRegister).toBe(true)
    expect(workflowReadiness({ ...evidence, baseline: { successful: true, exitCode: 0, target, baselineRegistered: true } }, target, now).canRegister).toBe(false)
  })

  it('requires post-registration status and health rather than older green diagnostics', () => {
    const baseline = { successful: true, exitCode: 0, target, baselineRegistered: true, observedAt: now }
    const oldStatus = { successful: true, exitCode: 0, target, observedAt: now - 60_000, output: '["1","BASELINE",1]' }
    expect(workflowReadiness({ candidate, image, backup, schema, baseline, status: oldStatus, health: oldStatus }, target, now).status).toBe(false)
    const newStatus = { ...oldStatus, observedAt: now + 60_000 }
    const ready = workflowReadiness({ candidate, image, backup, schema, baseline, status: newStatus, health: newStatus }, target, now)
    expect(ready.status && ready.health).toBe(true)
    expect(workflowReadiness({ candidate, image, backup, schema, baseline,
      status: { ...newStatus, output: '["2","SQL",1]' }, health: newStatus }, target, now).status).toBe(false)
  })

  it('requires a real successful version-one baseline row, not a green exit code alone', () => {
    expect(hasVersionOneBaseline('["1","BASELINE",1]')).toBe(true)
    expect(hasVersionOneBaseline('["1","BASELINE",true]\n["2","SQL",1]')).toBe(true)
    expect(hasVersionOneBaseline('["1","BASELINE",0]')).toBe(false)
    expect(hasVersionOneBaseline('no history')).toBe(false)
  })

  it('parses only direct fixed-task receipts, not model prose', () => {
    expect(fixedToolReceipt('固定任务工具已执行；以下为原始工具结果（是否成功以 successful、exitCode 为准），不是模型推测：\n\n```json\n{"successful":true,"exitCode":0}\n```'))
      .toEqual({ successful: true, exitCode: 0 })
    expect(fixedToolReceipt('模型说：{"successful":true}')).toBeUndefined()
    expect(backupAgeMinutes(backup.backupId, now)).toBeCloseTo(5.52, 1)
  })

  it('never labels an application rollback or uncertain release as successful publishing', () => {
    const receipt = { task: 'PUBLISH_RELEASE', successful: true, exitCode: 0, deployed: true, rolledBack: false, manualInterventionRequired: false }
    expect(fixedToolSucceeded(receipt)).toBe(true)
    expect(fixedToolSucceeded({ ...receipt, rolledBack: true })).toBe(false)
    expect(fixedToolSucceeded({ ...receipt, manualInterventionRequired: true })).toBe(false)
    expect(fixedToolSucceeded({ ...receipt, deployed: false })).toBe(false)
  })
})
