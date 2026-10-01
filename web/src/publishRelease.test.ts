import { describe, expect, it } from 'vitest'
import { publishReadiness, type ProductionStatus } from './publishRelease'
import type { WorkflowEvidence } from './releaseWorkflow'

describe('controlled publishing readiness', () => {
  const now = Date.parse('2026-10-01T08:00:00Z'); const target = 'fixed'
  const base = { successful: true, exitCode: 0, target }
  const id = '20261001T075000Z-1234abcd'; const sha = 'a'.repeat(64)
  const evidence: WorkflowEvidence = { candidate: { ...base, releaseId: id, manifestSha256: sha }, image: { ...base, releaseId: id, manifestSha256: sha, stage: 'IMAGE_READY', imageId: `sha256:${sha}` },
    backup: { ...base, backupId: id, manifestSha256: sha }, schema: { ...base, schemaComplete: true, schemaSha256: sha, observedAt: now - 1000 } as WorkflowEvidence['schema'],
    status: { ...base, output: '["1","BASELINE",1]', observedAt: now - 1000 } }
  const production: ProductionStatus = { ...base, currentImageId: `sha256:${'b'.repeat(64)}`, productionSha256: sha, appHealth: 'healthy', observedAt: now - 1000 }
  it('binds eight identities, not arbitrary options', () => {
    const result = publishReadiness(evidence, production, target, now)
    expect(result.ready).toBe(true); expect(Object.keys(result.args)).toHaveLength(8)
  })
  it('blocks stale, mixed target, repeated or unhealthy production', () => {
    for (const override of [{ target: 'other' }, { observedAt: now - 301_000 }, { appHealth: 'unhealthy' }, { currentImageId: `sha256:${sha}` }])
      expect(publishReadiness(evidence, { ...production, ...override }, target, now).ready).toBe(false)
    expect(publishReadiness(evidence, production, target, now, now - 500).ready).toBe(false)
    expect(publishReadiness({ ...evidence, backup: { ...evidence.backup!, backupId: '20261001T070000Z-1234abcd' } }, production, target, now).ready).toBe(false)
  })
})
