import { describe, expect, it } from 'vitest'
import { runConclusion, shouldSubmitOnEnter, statusLabel } from './uiPresentation'

describe('日常界面的输入与结果提示', () => {
  it('中文输入法确认和换行不提交', () => {
    expect(shouldSubmitOnEnter({ key: 'Enter', shiftKey: false, isComposing: true })).toBe(false)
    expect(shouldSubmitOnEnter({ key: 'Enter', shiftKey: false, isComposing: false, keyCode: 229 })).toBe(false)
    expect(shouldSubmitOnEnter({ key: 'Enter', shiftKey: true, isComposing: false })).toBe(false)
    expect(shouldSubmitOnEnter({ key: 'Enter', shiftKey: false, isComposing: false })).toBe(true)
  })
  it('会话完成不能掩盖工具失败', () => {
    expect(runConclusion({ status: 'COMPLETED', steps: [{ stepType: 'TOOL_RESULT', status: 'COMPLETED', outputText: '{"successful":false,"exitCode":1}' }] })).toContain('未成功')
    expect(statusLabel('COMPLETED')).toBe('已结束')
  })
  it('无工具失败证据时不宣称所有操作成功', () => {
    expect(runConclusion({ status: 'COMPLETED', steps: [] })).toContain('以工具回执为准')
    expect(runConclusion({ status: 'FAILED', errorMessage: '连接中断', steps: [] })).toBe('连接中断')
  })
})
