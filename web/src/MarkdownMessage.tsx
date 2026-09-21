import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

type MarkdownMessageProps = {
  content: string
}

export function safeMarkdownUrl(value: string): string {
  const url = value.trim()
  if (url.startsWith('#')) return url
  try {
    const protocol = new URL(url).protocol.toLowerCase()
    return protocol === 'http:' || protocol === 'https:' || protocol === 'mailto:' ? url : ''
  } catch {
    return ''
  }
}

export function MarkdownMessage({ content }: MarkdownMessageProps) {
  return <div className="markdown-message">
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      skipHtml
      urlTransform={safeMarkdownUrl}
      components={{
        a: ({ node: _node, href, children, ...props }) => href
          ? <a {...props} href={href} target={href.startsWith('#') ? undefined : '_blank'} rel={href.startsWith('#') ? undefined : 'noreferrer noopener'}>{children}</a>
          : <span>{children}</span>,
        img: ({ alt }) => <span className="markdown-image-placeholder">[图片：{alt || '未命名'}]</span>,
        table: ({ node: _node, children, ...props }) => <div className="markdown-table-scroll"><table {...props}>{children}</table></div>,
      }}
    >{content}</ReactMarkdown>
  </div>
}
