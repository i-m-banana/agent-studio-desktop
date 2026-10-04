import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { receiptOutcome, presentToolResult } from './toolResults'
import { ToolResultCard } from './ToolResultCard'

const preview = { successful: true, previewId: 'historical-preview', state: 'READY', sourceSha256: 'a'.repeat(64),
  database: 'H2_SYNTHETIC', mysqlIntegrationTested: false }
const publish = { task: 'PUBLISH_RELEASE', successful: true, exitCode: 0, deployed: true,
  rolledBack: false, manualInterventionRequired: false }

describe('typed tool result evidence', () => {
  it('recognizes historical and new ready previews without inventing a process exit code', () => {
    expect(receiptOutcome(preview)).toBe('success')
    expect(receiptOutcome({ ...preview, task: 'START_PROJECT_PREVIEW', receiptVersion: 1 })).toBe('success')
    expect(presentToolResult(preview).scope).toContain('没有验证 MySQL')
    expect(presentToolResult(preview).result).toBe('预览启动时已就绪')
  })
  it('does not mark stopped, incomplete, truncated or failed previews ready', () => {
    for (const change of [{ state: 'STOPPED' }, { sourceSha256: undefined }, { database: undefined },
      { outputTruncated: true }, { successful: false }]) expect(receiptOutcome({ ...preview, ...change })).not.toBe('success')
  })
  it('never treats missing, unknown or truncated publication evidence as success', () => {
    expect(receiptOutcome(publish)).toBe('success')
    for (const change of [{ exitCode: undefined }, { outputTruncated: true }, { deployed: undefined },
      { manualInterventionRequired: true }, { rolledBack: true }]) expect(receiptOutcome({ ...publish, ...change })).not.toBe('success')
    expect(receiptOutcome({ successful: true, exitCode: 0 })).toBe('unknown')
    expect(receiptOutcome({ task: 'NEW_UNREVIEWED_TOOL', successful: true, exitCode: 0 })).toBe('unknown')
  })
  it('distinguishes application recovery from successful publishing', () => {
    const r = presentToolResult({ ...publish, successful: false, exitCode: 1, deployed: false, rolledBack: true })
    expect(r.outcome).toBe('restored')
    expect(r.impact).toContain('不会随应用恢复自动撤销')
    expect(r.next).toContain('不要重新上线')
  })
  it('does not infer absent baseline from a read-only schema receipt', () => {
    const r = presentToolResult({ task: 'DATABASE_SCHEMA', successful: true, exitCode: 0, schemaComplete: true, baselineRegistered: false })
    expect(r.outcome).toBe('success')
    expect(r.scope).toContain('不表示基线不存在')
  })
  it('preserves successful historical image builds with incomplete current smoke evidence', () => {
    const r = { releaseId: 'r', imageId: 'sha256:x', stage: 'IMAGE_READY', successful: true, exitCode: 0 }
    expect(receiptOutcome(r)).toBe('unknown')
    expect(r.successful).toBe(true)
    expect(receiptOutcome({ ...r, output: 'AGENTSTUDIO_STAGE=RUNTIME_SMOKE' })).toBe('success')
  })
  it('renders readable scope, impact and action while folding the raw receipt and unsafe URL', () => {
    const html = renderToStaticMarkup(<ToolResultCard receipt={{ ...preview, url: 'javascript:alert(1)' }} createdAt="2026-10-03T15:58:31Z" />)
    for (const text of ['历史结果', '网站隔离预览', '验证范围', '影响', '下一步', '<details>']) expect(html).toContain(text)
    expect(html).not.toContain('<details open')
    expect(html).not.toContain('href=')
    expect(html).not.toContain('fixed-tool-reply--failed')
  })
})
