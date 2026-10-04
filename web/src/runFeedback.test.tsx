import { renderToStaticMarkup } from 'react-dom/server'
import { describe,it,expect } from 'vitest'
import { RunDock } from './RunDock'
import { stageLabel,terminalRun } from './runFeedback'

describe('任务反馈和独立审批',()=>{
  it('模型连接重试能看见原因，不显示虚构进度',()=>{
    const html=renderToStaticMarkup(<RunDock active={true} activity={{id:'r',status:'THINKING',startedAt:new Date().toISOString(),checkedAt:new Date().toISOString(),lastStep:{stepType:'MODEL_CONNECTION_RETRY'}}} approvalSeconds={0} deciding={false} stopping={false} onDecide={()=>{}} onStop={()=>{}} onRetry={()=>{}} />)
    expect(html).toContain('模型连接中断，正在重新连接');expect(html).not.toContain('%')
  })
  it('只把终态判断为结束，等待审批和停止请求仍是进行中的状态',()=>{
    expect(terminalRun('CANCEL_REQUESTED')).toBe(false)
    expect(terminalRun('WAITING_APPROVAL')).toBe(false)
    expect(terminalRun('TIMED_OUT')).toBe(true)
    expect(stageLabel('TOOL_RUNNING','tool')).toContain('执行')
  })
  it('审批总能展示原参数，过期后不能批准',()=>{
    const html=renderToStaticMarkup(<RunDock active={true} approval={{id:'a',toolName:'tool',capability:'WRITE',riskLevel:'HIGH',targetEnvironment:'fixture',argumentsJson:'{"path":"fixture.md"}',argumentsSha256:'a'.repeat(64),expiresAt:'2020-01-01T00:00:00Z'}} approvalSeconds={0} deciding={false} stopping={false} onDecide={()=>{}} onStop={()=>{}} onRetry={()=>{}} />)
    expect(html).toContain('run-dock--approval');expect(html).toContain('fixture.md')
    expect(html).toContain('审批已过期');expect(html).toMatch(/disabled=""[^>]*>批准执行一次/)
  })
  it('未确认状态不会伪装成已确认，也不显示虚构百分比',()=>{
    const html=renderToStaticMarkup(<RunDock active={true} error="offline" approvalSeconds={0} deciding={false} stopping={false} runId="r" onDecide={()=>{}} onStop={()=>{}} onRetry={()=>{}} />)
    expect(html).toContain('暂时无法确认后台状态');expect(html).toContain('停止运行');expect(html).not.toContain('%')
    expect(html).toContain('后台连接中断，任务状态待确认');expect(html).toContain('计时不代表任务仍在执行')
    expect(html).not.toContain('可以继续等待');expect(html).not.toContain('waiting-pulse')
  })
  it('工具状态过期后只展示最后确认的信息，收起也仍提示连接中断',()=>{
    const html=renderToStaticMarkup(<RunDock active={true} activity={{id:'r',status:'TOOL_RUNNING',startedAt:new Date().toISOString(),checkedAt:new Date().toISOString()}} checkedAt={Date.now()-30000} toolLabel="工作区验证" approvalSeconds={0} deciding={false} stopping={false} onDecide={()=>{}} onStop={()=>{}} onRetry={()=>{}} />)
    expect(html).toContain('最后确认的工具');expect(html).not.toContain('正在执行工具任务');expect(html).not.toContain('waiting-pulse')
  })
  it('连接中断时保留审批详情，但不能批准未经重新确认的旧审批',()=>{
    const html=renderToStaticMarkup(<RunDock active={true} error="offline" approval={{id:'a',toolName:'verify',capability:'EXECUTE',riskLevel:'HIGH',targetEnvironment:'fixture',argumentsJson:'{}',argumentsSha256:'a'.repeat(64),expiresAt:new Date(Date.now()+60000).toISOString()}} approvalSeconds={60} deciding={false} stopping={false} onDecide={()=>{}} onStop={()=>{}} onRetry={()=>{}} />)
    expect(html).toContain('重新确认');expect(html).toContain('连接恢复并核对当前审批')
    expect(html).toMatch(/disabled=""[^>]*>批准执行一次/)
  })
})
