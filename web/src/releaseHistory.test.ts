import { describe, it, expect } from 'vitest'
import { historyEvidence, knownBaseline, type ReleaseTask } from './releaseHistory'
const task = (id: string, conversationId: string, toolName = 'prepare_release_candidate', extra: Partial<ReleaseTask> = {}): ReleaseTask => ({
  id, conversationId, toolName, agentVersionId: 'version', runId: id, status: 'SUCCEEDED',
  createdAt: '2026-10-01T10:00:00Z', observedAt: '2026-10-01T10:01:00Z', target: 'target', releaseId: id,
  receipt: { successful: true, exitCode: 0, releaseId: id, manifestSha256: 'a'.repeat(64) },
  sourceStep: { id }, auditEvents: [], nextAction: '', ...extra,
})
describe('history evidence provenance', () => {
  it('blocks repeat baseline registration using verified target facts without restoring another session evidence', () => {
    const baseline=task('baseline','old','adopt_remote_database_baseline',{receipt:{baselineRegistered:true}})
    expect(knownBaseline([baseline],'target')).toBe(true)
    expect(knownBaseline([baseline],'other')).toBe(false)
    expect(knownBaseline([{...baseline,status:'UNKNOWN'}],'target')).toBe(false)
    expect(historyEvidence([baseline],'new',undefined,'target')).toEqual([])
  })
  it('does not merge other sessions or targets implicitly', () => {
    expect(historyEvidence([task('one','session'),task('other','other'),task('foreign','session',undefined,{ target: 'foreign' })], 'session',undefined,'target').map(t=>t.id)).toEqual(['one'])
    expect(historyEvidence([task('one','session')], undefined,undefined,'target')).toEqual([])
  })
  it('explicit selection includes only the candidate and identity-matched image', () => {
    const tasks = [task('chosen','old'),task('current','new'),task('image','old','build_release_candidate_image', {
      releaseId:'chosen',receipt:{ releaseId:'chosen',manifestSha256:'a'.repeat(64) } }),task('wrong','new','build_release_candidate_image')]
    expect(historyEvidence(tasks,'new','chosen','target').map(t=>t.id)).toEqual(['chosen','image'])
    expect(historyEvidence(tasks,'new','missing','target')).toEqual([])
  })
  it('uses original receipt time and preserves stale backups', () => {
    const backup = task('backup','session','prepare_remote_deployment_backup')
    const result = historyEvidence([backup],'session',undefined,'target')[0]
    expect(result.observedAt).toBe(Date.parse(backup.observedAt))
    expect(JSON.parse(result.outputText).successful).toBe(true)
  })
  it('unknown or rejected newer attempts invalidate earlier success', () => {
    const unknown = task('unknown','session','build_release_candidate_image',{status:'UNKNOWN',observedAt:'2026-10-01T10:02:00Z',receipt:undefined})
    const evidence = historyEvidence([unknown,task('old','session','build_release_candidate_image')],'session',undefined,'target')
    expect(JSON.parse(evidence.at(-1)!.outputText).successful).toBe(false)
  })
  it('an unknown newer attempt still invalidates a selected candidate image by its request identity', () => {
    const chosen=task('chosen','old')
    const image=task('image','old','build_release_candidate_image',{releaseId:'chosen',receipt:{manifestSha256:'a'.repeat(64),successful:true}})
    const unknown=task('unknown','old','build_release_candidate_image',{releaseId:'chosen',status:'UNKNOWN',target:undefined,receipt:undefined,
      observedAt:'2026-10-01T10:02:00Z',sourceStep:{id:'unknown',inputJson:JSON.stringify({releaseId:'chosen',manifestSha256:'a'.repeat(64)})}})
    const evidence=historyEvidence([chosen,image,unknown],'new','chosen','target')
    expect(JSON.parse(evidence.at(-1)!.outputText).successful).toBe(false)
  })
})
