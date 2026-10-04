import { describe,it,expect } from 'vitest'
import { explainToolFailure } from './toolFailure'
import { receiptOutcome } from './toolResults'
describe('failure explanations and MySQL proof',()=>{
  it('treats connection loss as unknown remote effects',()=>{expect(explainToolFailure({successful:false,stage:'BUILD',output:'Connection reset'})).toContain('可能仍执行');expect(explainToolFailure({successful:false,output:'timeout'})).toContain('不能证明')})
  it('does not turn complete success into a failure based on old log words',()=>expect(explainToolFailure({successful:true,exitCode:0,output:'previous timeout'})).toBeUndefined())
  it('requires three full suite reports, zero exit and confirmed cleanup for MySQL success',()=>{const reports=['com.mylove.MySqlMigrationSmokeIT','com.mylove.DatabaseBaselineMaintenanceIT','com.mylove.database.DatabaseReleaseMaintenanceIT'].map(suite=>({suite,tests:2,failures:0,errors:0,skipped:0,passed:true}));const r={task:'MYSQL_INTEGRATION',successful:true,exitCode:0,allRequiredSuitesPassed:true,cleanupConfirmed:true,reports};expect(receiptOutcome(r)).toBe('success');for(const change of [{exitCode:1},{allRequiredSuitesPassed:false},{cleanupConfirmed:false},{reports:[]}])expect(receiptOutcome({...r,...change})).not.toBe('success');expect(receiptOutcome({...r,outputTruncated:true})).toBe('success')})
})
