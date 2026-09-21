import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { MarkdownMessage, safeMarkdownUrl } from './MarkdownMessage'

describe('MarkdownMessage', () => {
  it('renders common GFM content including tables and fenced code', () => {
    const html = renderToStaticMarkup(<MarkdownMessage content={'## Result\n\n- **ready**\n\n|task|exit|\n|---|---:|\n|build|0|\n\n```sh\nnpm test\n```'} />)

    expect(html).toContain('<h2>Result</h2>')
    expect(html).toContain('<strong>ready</strong>')
    expect(html).toContain('markdown-table-scroll')
    expect(html).toContain('<table>')
    expect(html).toContain('<code class="language-sh">npm test')
  })

  it('drops raw HTML and deactivates dangerous links', () => {
    const html = renderToStaticMarkup(<MarkdownMessage content={'<img src=x onerror="alert(1)">\n\n[unsafe](javascript:alert(1))\n\n![tracker](https://tracker.example/pixel.png)'} />)

    expect(html).not.toContain('<img')
    expect(html).not.toContain('javascript:')
    expect(html).not.toContain('<a')
    expect(html).toContain('<span>unsafe</span>')
    expect(html).toContain('[图片：tracker]')
    expect(html).not.toContain('tracker.example')
  })

  it('isolates safe external links and accepts incomplete streaming text', () => {
    const html = renderToStaticMarkup(<MarkdownMessage content={'[docs](https://example.com)\n\n**still streaming'} />)

    expect(html).toContain('href="https://example.com"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noreferrer noopener"')
    expect(html).toContain('**still streaming')
  })
})

describe('safeMarkdownUrl', () => {
  it('allows only anchors and explicit http, https, or mailto URLs', () => {
    expect(safeMarkdownUrl('#result')).toBe('#result')
    expect(safeMarkdownUrl('https://example.com')).toBe('https://example.com')
    expect(safeMarkdownUrl('mailto:test@example.com')).toBe('mailto:test@example.com')
    expect(safeMarkdownUrl('javascript:alert(1)')).toBe('')
    expect(safeMarkdownUrl('/relative/path')).toBe('')
  })
})
