import { describe, it, expect } from 'vitest'
import { previewUrl } from './ProjectPreview'

describe('preview links are fixed to a separate loopback origin', () => {
  it('allows only the managed local origin', () => { expect(previewUrl('http://127.0.0.2:12345')).toBe('http://127.0.0.2:12345') })
  it('rejects remote, platform-origin and unsafe URLs', () => {
    for (const url of ['http://localhost:8080', 'http://127.0.0.1:8080', 'https://example.com', 'javascript:alert(1)', 'http://user@127.0.0.2:12345', 'http://127.0.0.2:12345/other']) expect(previewUrl(url)).toBeUndefined()
  })
})
