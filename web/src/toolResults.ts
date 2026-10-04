/** Display-only projection of tool receipts. Never authorizes execution or fresh evidence. */
export type ToolResult = {
  kind: string; title: string; outcome: 'success' | 'failed' | 'unknown' | 'restored'
  result: string; scope: string; impact: string; next: string
}

const tasks: Record<string, string> = {
  MAVEN_TEST: 'Java 测试', MYSQL_INTEGRATION: 'MySQL 集成验证', NPM_TEST: '前端测试', NPM_BUILD: '前端构建',
  PUBLISH_RELEASE: '正式上线', START_PROJECT_PREVIEW: '网站隔离预览',
  DATABASE_SCHEMA: '数据库结构核查', DATABASE_BASELINE_STATUS: '数据库版本历史',
  RELEASE_STATUS: '线上版本核查', SITE_HEALTH: '网站入口健康检查',
  COMPOSE_VALIDATE: '部署配置校验', COMPOSE_STATUS: '服务状态核查', NGINX_VALIDATE: 'Nginx 配置校验',
  RELEASE_FINGERPRINT: '发布文件核查', GIT_STATUS: 'Git 状态检查', GIT_DIFF_SUMMARY: '代码变更摘要',
}

export function baselineRegistration(r: Record<string, unknown>) {
  return typeof r.task !== 'string' && 'baselineVersion' in r && 'baselineRegistered' in r
}

export function receiptKind(r: Record<string, unknown>): string {
  if (typeof r.task === 'string') return r.task
  // Historical preview receipts had no task or process exit code.
  if (typeof r.previewId === 'string' && typeof r.state === 'string') return 'START_PROJECT_PREVIEW'
  if (baselineRegistration(r)) return 'BASELINE_REGISTRATION'
  if ('releaseId' in r && 'imageId' in r) return 'RELEASE_IMAGE'
  if ('backupId' in r && 'drillId' in r) return 'BACKUP_DRILL'
  if ('backupId' in r && 'databaseBytes' in r) return 'BACKUP'
  if ('releaseId' in r) return 'RELEASE_CANDIDATE'
  return 'UNKNOWN'
}

export function receiptOutcome(r: Record<string, unknown>): ToolResult['outcome'] {
  const kind = receiptKind(r)
  const ok = r.successful === true && r.exitCode === 0
  if(kind==='MYSQL_INTEGRATION'){
    if(r.successful===false||typeof r.exitCode==='number'&&r.exitCode!==0)return 'failed'
    const reports=Array.isArray(r.reports)?r.reports as Record<string,unknown>[]:[]
    const names=['com.mylove.MySqlMigrationSmokeIT','com.mylove.DatabaseBaselineMaintenanceIT','com.mylove.database.DatabaseReleaseMaintenanceIT']
    const complete=names.every(name=>reports.some(v=>v.suite===name&&Number(v.tests)>0&&v.failures===0&&v.errors===0&&v.skipped===0&&v.passed===true))
    return ok&&r.allRequiredSuitesPassed===true&&r.cleanupConfirmed===true&&complete?'success':'unknown'
  }
  if (r.outputTruncated === true) return r.successful === false ? 'failed' : 'unknown'
  if (kind === 'PUBLISH_RELEASE') {
    if (ok && r.deployed === true && r.rolledBack === false && r.manualInterventionRequired === false) return 'success'
    if (r.successful === false && typeof r.exitCode === 'number' && r.exitCode !== 0 && r.deployed === false
      && r.rolledBack === true && r.manualInterventionRequired === false) return 'restored'
    return 'unknown'
  }
  if (r.successful === false || (typeof r.exitCode === 'number' && r.exitCode !== 0)) return 'failed'
  if (kind === 'START_PROJECT_PREVIEW') {
    return r.successful === true && r.state === 'READY' && typeof r.previewId === 'string'
      && typeof r.sourceSha256 === 'string' && /^[0-9a-f]{64}$/.test(r.sourceSha256)
      && r.database === 'H2_SYNTHETIC' ? 'success' : 'unknown'
  }
  if (!ok) return 'unknown'
  if (kind === 'DATABASE_SCHEMA') return r.schemaComplete === true ? 'success' : 'unknown'
  if (kind === 'BASELINE_REGISTRATION') return r.baselineRegistered === true ? 'success' : 'unknown'
  if (kind === 'RELEASE_IMAGE') return r.stage === 'IMAGE_READY' && typeof r.output === 'string'
    && r.output.includes('AGENTSTUDIO_STAGE=RUNTIME_SMOKE') ? 'success' : 'unknown'
  if (kind === 'RELEASE_CANDIDATE') return r.stage === 'REMOTE_VERIFY' && typeof r.releaseId === 'string'
    && typeof r.manifestSha256 === 'string' && /^[0-9a-f]{64}$/.test(r.manifestSha256) ? 'success' : 'unknown'
  return kind in tasks || ['BACKUP', 'BACKUP_DRILL'].includes(kind) ? 'success' : 'unknown'
}

export function presentToolResult(r: Record<string, unknown>): ToolResult {
  const kind = receiptKind(r), outcome = receiptOutcome(r)
  const title = tasks[kind] ?? ({ BASELINE_REGISTRATION: '首次数据库基线登记', RELEASE_IMAGE: '候选镜像构建',
    RELEASE_CANDIDATE: '发布候选准备', BACKUP: '发布前备份', BACKUP_DRILL: '备份恢复材料演练' } as Record<string, string>)[kind] ?? '工具结果'
  let result = outcome === 'success' ? '执行成功' : outcome === 'failed' ? '执行失败' : outcome === 'restored' ? '上线失败，旧应用已恢复' : '结果未确认'
  let scope = '只覆盖本次工具执行；不代表整个需求或发布流程已完成。'
  let impact = '回执没有充分说明影响范围，请核对运行记录。'
  let next = '在 08 运行记录打开来源运行，核对执行结果与审计；不要直接重试生产操作。'
  if (kind === 'START_PROJECT_PREVIEW') {
    if (outcome === 'success') result = '预览启动时已就绪'
    scope = 'H2 合成数据预览；没有验证 MySQL 集成、生产迁移或真实数据。'
    impact = outcome === 'success' ? '占用本机隔离容器和临时空间；不连接生产，预览会到期停止。' : '可能有本机容器或临时资源需要回收；不接入生产。'
    next = outcome === 'success' ? '在对话工具栏点击“项目” → “网站隔离预览”，核对当前状态后打开预览；验收结束停止预览。'
      : '在“项目” → “网站隔离预览”查看当前状态；若待回收，先处理该预览，勿重复启动。'
  } else if(kind==='MYSQL_INTEGRATION'){
    scope='专用 MySQL 8.0 合成数据，执行三组固定集成测试；平台适配容器生命周期，不验证生产数据或 Testcontainers 的 Docker 管理。'
    impact=r.cleanupConfirmed===true?'本次本机隔离容器和网络已回收；没有修改业务源码或生产。':'本次隔离资源待核对或回收；不能反复创建。'
    next=outcome==='success'?'继续预览验收；上线仍需独立准备和审批。':'在 05 项目管理 → 预览与测试设置查看报告；待回收时先回收原资源。'
  } else if (['MAVEN_TEST', 'NPM_TEST', 'NPM_BUILD'].includes(kind)) {
    scope = kind === 'NPM_BUILD' ? '仅前端构建；不等于页面体验或生产验证通过。' : '仅固定任务实际运行的测试；未运行的集成测试、浏览器验收和生产验证不在其中。'
    impact = r.isolated === true ? '在隔离源码副本中执行；不修改业务源码或生产数据。' : '会生成测试或构建产物；是否隔离以原始回执为准。'
    next = outcome === 'success' ? '需要检查页面效果时，在项目面板请求网站隔离预览，再人工查看。' : '在运行记录查看失败日志，定位原因后重新请求验证。'
    if (r.failureCategory === 'DEPENDENCY_CACHE_MISSING') next = '验证镜像缺少离线依赖；先补齐可信缓存再验证，不开放生产网络。'
  } else if (['DATABASE_SCHEMA', 'DATABASE_BASELINE_STATUS', 'RELEASE_STATUS', 'SITE_HEALTH', 'COMPOSE_VALIDATE', 'COMPOSE_STATUS', 'NGINX_VALIDATE', 'RELEASE_FINGERPRINT', 'GIT_STATUS', 'GIT_DIFF_SUMMARY'].includes(kind)) {
    scope = kind === 'DATABASE_SCHEMA' ? '核查结构，不登记数据库基线；baselineRegistered:false 不表示基线不存在。'
      : kind === 'SITE_HEALTH' ? '仅入口健康；不代表全部页面或业务功能通过。' : '核查当时的线上状态；历史记录不代表网站当前状态。'
    impact = '只读检查，没有通过本工具切换应用或登记基线。'
    next = outcome === 'success' ? '回到 07 远程工作台继续本次发布；证据过期时只更新对应核查。' : '核对失败原因；结果未确认时停止上线。'
  } else if (kind === 'PUBLISH_RELEASE') {
    if (outcome === 'success') result = '已上线，健康验证通过'
    scope = '本次应用发布和健康验证；不等于全部业务验收或真实故障恢复演练通过。'
    impact = outcome === 'success' ? `生产应用已切换。${r.databaseMayHaveChanged === false ? '回执确认本次未改变数据库。' : '数据库迁移与影响以原始回执为准。'}`
      : outcome === 'restored' ? '旧应用已恢复并验证健康；数据库扩展不会随应用恢复自动撤销。' : '生产影响尚未确认；保留原尝试和所有备份。'
    next = outcome === 'success' ? '在 07 远程工作台读取线上版本、检查网站健康，再打开网站验收业务效果。'
      : '先读取线上版本、数据库版本历史和网站健康；核查完成前不要重新上线。'
  } else if (kind === 'RELEASE_CANDIDATE' || kind === 'RELEASE_IMAGE') {
    scope = kind === 'RELEASE_CANDIDATE' ? '本次源码快照的测试与打包；不表示已经上线。' : '镜像构建与非 root 维护入口自检；无效参数被拒绝是预期行为，不等于完整业务或数据库验证。'
    impact = outcome === 'success' ? '创建候选或镜像及日志；没有通过本工具切换生产应用。' : '可能留下候选、构建资源或日志；没有通过本工具切换生产应用。'
    next = outcome === 'success' ? (kind === 'RELEASE_CANDIDATE' ? '在 07 远程工作台继续构建该候选镜像；材料保持同一候选。' : '在 07 远程工作台创建或核对有效备份，继续只读上线核查。') : '先查看失败阶段与残留材料；不重建已经成功的前置阶段。'
  } else if (kind === 'BACKUP') {
    scope = '备份创建和材料校验；不等于恢复演练通过。'
    impact = outcome === 'success' ? '新增备份，占用服务器空间；所有备份保留。' : '可能留下未完成的备份；所有备份保留，不自动删除。'
    next = outcome === 'success' ? '继续上线前只读核查；超过 30 分钟需新建合格备份，旧备份不删除。' : '停止上线；检查备份日志及完整性。'
  } else if (kind === 'BACKUP_DRILL') {
    scope = '隔离恢复材料演练；不是生产故障恢复演练。'
    impact = '仅演练资源与临时产物；不将备份导入生产。'
  } else if (kind === 'BASELINE_REGISTRATION') {
    scope = '首次登记版本 1 基线；日常发布不重复登记。'
    impact = '写入生产数据库版本历史；不等于业务迁移或应用上线。'
    next = outcome === 'success' ? '重新核查版本历史、线上版本和健康，再进入日常发布。' : '停止重复登记；先只读核查现有版本历史。'
  }
  if (r.outputTruncated === true) scope += kind==='MYSQL_INTEGRATION'&&outcome==='success'?' 日志已截断；测试结论来自三组完整 XML 报告。':' 输出已截断，证据不足；不能据此确认成功。'
  return { kind, title, outcome, result, scope, impact, next }
}
