import { describe, expect, it } from 'vitest'
import { releaseImageRequest } from './releaseImage'

describe('候选镜像请求', () => {
  it('只绑定精确候选身份与摘要', () => {
    const message = releaseImageRequest('20260928T081518Z-080d3c7e', 'a'.repeat(64))
    expect(message).toContain('build_release_candidate_image')
    expect(message).toContain(JSON.stringify({ releaseId: '20260928T081518Z-080d3c7e', manifestSha256: 'a'.repeat(64) }))
  })
  it('拒绝路径、注入和不完整摘要', () => {
    for (const id of ['../latest', '20260928T081518Z-080d3c7e;reboot', 'latest']) {
      expect(() => releaseImageRequest(id, 'a'.repeat(64))).toThrow()
    }
    expect(() => releaseImageRequest('20260928T081518Z-080d3c7e', 'a'.repeat(63))).toThrow()
  })
})
