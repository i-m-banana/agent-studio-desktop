import { describe, expect, it } from 'vitest'
import { matchesText, readablePreview, conversationMarkdown, followingLatest, nextFileLine } from './listTools'

describe('查找与阅读', () => {
  it('支持中文与跨字段关键词，特殊字符按文字匹配', () => {
    expect(matchesText('远程 READY', '远程部署助手', 'ready')).toBe(true)
    expect(matchesText('%', '普通正文')).toBe(false)
    expect(matchesText('[', '含有[括号]')).toBe(true)
    expect(matchesText('不存在', '远程助手')).toBe(false)
  })
  it('只为固定工具请求提供可读标题，保留普通原文', () => {
    expect(readablePreview('请只使用 prepare_release_candidate 工具，从固定源码')).toBe('准备发布候选')
    expect(readablePreview('普通\n测试问题')).toBe('普通 测试问题')
  })
  it('主动导出保留原始消息与版本身份', () => {
    const text = conversationMarkdown([{role:'user',content:'```\n原始正文\n```',createdAt:'2026-10-02T00:00:00Z'}], 'v-id', 'c-id')
    expect(text).toContain('原始正文'); expect(text).toContain('v-id'); expect(text).toContain('c-id'); expect(text).toContain('2026-10-02T00:00:00Z')
  })
  it('阅读旧消息时不自动跟随；分页不跳过被输出限制截断的行', () => {
    expect(followingLatest(200,500,2000)).toBe(false)
    expect(followingLatest(1450,500,2000)).toBe(true)
    expect(nextFileLine(1,73,800)).toBe(74)
    expect(nextFileLine(1,0,800)).toBeUndefined()
    expect(nextFileLine(601,800,800)).toBeUndefined()
  })
})
