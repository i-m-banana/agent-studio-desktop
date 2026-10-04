export type MysqlVerification = { sourceSha256?:string; successful?:boolean; allRequiredSuitesPassed?:boolean; cleanupConfirmed?:boolean }
export function mysqlVerifiedForSource(source:string | undefined, records:MysqlVerification[]) {
  return Boolean(source && records.some(record=>record.sourceSha256===source && record.successful===true
    && record.allRequiredSuitesPassed===true && record.cleanupConfirmed===true))
}
export function localVerificationVersion(versions:{id:string;toolNames:string[]}[],current:string) {
  const local=versions.filter(version=>version.toolNames.includes('run_workspace_verification'))
  return local.find(version=>version.id===current)?.id ?? (local.length===1 ? local[0].id : undefined)
}
