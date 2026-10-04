import { describe, expect, it } from 'vitest'
import { projectApproval } from './projectApproval'
import { historyEvidence } from './releaseHistory'
describe('project-bound approval and release evidence', () => {
  it('shows project and real path without conflating target metadata', () => {
    expect(projectApproval('PROJECT:网站|ID:p|REV:2|WORKSPACE:D:\\projects\\site#identity|TARGET:LOCAL|FILE:src/App.java|PARENT:path')).toEqual({ project: '网站', root: 'D:\\projects\\site', file: 'src/App.java', isolated: false })
    expect(projectApproval('SSH:user@host')).toBeUndefined()
  })
  it('does not reuse another project candidate or unbound historical source', () => {
    const task = { id:'t', runId:'r', conversationId:'c',agentVersionId:'v',toolName:'prepare_release_candidate',status:'SUCCEEDED',createdAt:'',observedAt:'',target:'host',releaseId:'release',receipt:{successful:true,projectId:'other'},sourceStep:{id:'s'},auditEvents:[],nextAction:'' }
    expect(historyEvidence([task],'c','t','host','mine')).toEqual([])
    expect(historyEvidence([task],'c','t','host','other')).toHaveLength(1)
  })
})
