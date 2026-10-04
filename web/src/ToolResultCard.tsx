import { presentToolResult } from './toolResults'
import { explainToolFailure } from './toolFailure'

export function ToolResultCard({ receipt, createdAt }: { receipt: Record<string, unknown>; createdAt?: string }) {
  const result = presentToolResult(receipt)
  const failure = explainToolFailure(receipt)
  return <div className={`fixed-tool-reply fixed-tool-reply--${result.outcome}`}>
    <strong>{createdAt ? '历史结果 · ' : ''}{result.title}：{result.result}</strong>
    {createdAt && <p className="hint">{new Date(createdAt).toLocaleString()} · 当前状态需重新核查</p>}
    <dl className="tool-result-facts">
      <div><dt>验证范围</dt><dd>{result.scope}</dd></div>
      <div><dt>影响</dt><dd>{result.impact}</dd></div>
      <div><dt>下一步</dt><dd>{result.next}</dd></div>
    </dl>
    {failure && <p className="run-error">{failure}</p>}
    {Array.isArray(receipt.reports)&&receipt.reports.length>0&&<ul>{(receipt.reports as Record<string,unknown>[]).map(r=><li key={String(r.suite)}>{String(r.suite).split('.').at(-1)}：运行 {String(r.tests)} 项，失败 {String(r.failures)}，错误 {String(r.errors)}，跳过 {String(r.skipped)}{Array.isArray(r.failedTests)&&r.failedTests.map((f:Record<string,unknown>,i:number)=><p key={i}>{String(f.test)}：{String(f.message)}</p>)}</li>)}</ul>}
    {receipt.reportSource==='ISOLATED_CURRENT_RUN'&&<p className="hint">报告来自本次隔离测试；项目目录中的旧报告不代表本次结果。</p>}
    {typeof receipt.reportWarning==='string'&&<p className="run-error">{receipt.reportWarning}</p>}
    <details><summary>技术详情与原始回执</summary><pre>{JSON.stringify(receipt, null, 2)}</pre></details>
  </div>
}
