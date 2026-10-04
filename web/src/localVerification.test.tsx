import { describe,it,expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { localVerificationVersion,mysqlVerifiedForSource } from './localVerification'
import { ReleaseVerificationHint } from './ReleaseWorkflowPanel'

describe('发布前本地 MySQL 验证入口',()=>{
  const passed={sourceSha256:'current-source',successful:true,allRequiredSuitesPassed:true,cleanupConfirmed:true}
  it('源码变更、未清理或未完成全部套件时不能沿用旧成功回执',()=>{
    expect(mysqlVerifiedForSource('current-source',[passed])).toBe(true)
    expect(mysqlVerifiedForSource('new-source',[passed])).toBe(false)
    expect(mysqlVerifiedForSource(undefined,[passed])).toBe(false)
    expect(mysqlVerifiedForSource('current-source',[{...passed,cleanupConfirmed:false}])).toBe(false)
    expect(mysqlVerifiedForSource('current-source',[{...passed,allRequiredSuitesPassed:false}])).toBe(false)
    expect(mysqlVerifiedForSource('current-source',[{...passed,successful:false}])).toBe(false)
  })
  it('只选择具有本地验证工具的助手，不把远程 Maven 当作 MySQL 集成工具',()=>{
    const remote={id:'remote',toolNames:['run_remote_workspace_task']}
    const local={id:'local',toolNames:['run_workspace_verification']}
    expect(localVerificationVersion([remote,local],'remote')).toBe('local')
    expect(localVerificationVersion([remote],'remote')).toBeUndefined()
    expect(localVerificationVersion([local,{...local,id:'another'}],'remote')).toBeUndefined()
    expect(localVerificationVersion([local,{...local,id:'another'}],'local')).toBe('local')
  })
  it('未取得对应源码回执时提供正确入口，不暗示可以直接上线',()=>{
    const html=renderToStaticMarkup(<ReleaseVerificationHint source="new-source" required={true} records={[passed]} busy={false} onVerify={()=>{}} />)
    expect(html).toContain('去 06 验证 MySQL');expect(html).toContain('尚未通过')
    expect(html).not.toContain('MySQL 合成验证已通过')
    const ready=renderToStaticMarkup(<ReleaseVerificationHint source="current-source" required={true} records={[passed]} busy={false} onVerify={()=>{}} />)
    expect(ready).toContain('MySQL 合成验证已通过');expect(ready).not.toContain('去 06')
  })
})
