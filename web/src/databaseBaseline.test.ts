import { expect, test } from 'vitest'
import { databaseBaselineRequest, mergeBaselineEvidence } from './databaseBaseline'
const args = { releaseId: '20260929T150000Z-1234abcd', manifestSha256: 'a'.repeat(64), imageId: `sha256:${'b'.repeat(64)}`, schemaSha256: 'c'.repeat(64), backupId: '20260929T151000Z-5678abcd', backupManifestSha256: 'd'.repeat(64) }
test('binds exactly six identities, not arbitrary commands', () => { expect(databaseBaselineRequest(JSON.stringify(args))).toContain(JSON.stringify(args)) })
test('rejects SQL overrides, missing fields, and forged image identities', () => {
  expect(() => databaseBaselineRequest(JSON.stringify({ ...args, sql: 'DROP TABLE users' }))).toThrow()
  expect(() => databaseBaselineRequest(JSON.stringify({ ...args, schemaSha256: '' }))).toThrow()
  expect(() => databaseBaselineRequest(JSON.stringify({ ...args, imageId: 'latest' }))).toThrow()
})
test('keeps current-chain evidence across run resets and only merges new identities', () => {
  const image = mergeBaselineEvidence('{}', { releaseId: args.releaseId, manifestSha256: args.manifestSha256, imageId: args.imageId })
  const reset = mergeBaselineEvidence(image, {})
  const backup = mergeBaselineEvidence(reset, { backupId: args.backupId, backupManifestSha256: args.backupManifestSha256 })
  const complete = mergeBaselineEvidence(backup, { schemaSha256: args.schemaSha256 })
  expect(databaseBaselineRequest(complete)).toContain('adopt_remote_database_baseline')
  expect(mergeBaselineEvidence('{unfinished', { imageId: args.imageId })).toBe('{unfinished')
})
