import { useEffect, useRef, useState } from 'react'
import { CopyButton } from './CopyButton'

export type TextPreview = { documentId: string; fileName: string; offset: number; nextOffset: number; totalChars: number; content: string; sha256: string }
export function DocumentReader({ document, onClose, onLoad }: {
  document: { id: string; fileName: string }; onClose: () => void; onLoad: (offset: number) => Promise<TextPreview>
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const request = useRef(0)
  const loader = useRef(onLoad); loader.current = onLoad
  const [preview, setPreview] = useState<TextPreview>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [offsets, setOffsets] = useState([0])
  async function load(offset: number, trail: number[]) {
    const token = ++request.current; setBusy(true); setError('')
    try {
      const next = await loader.current(offset)
      if (token !== request.current) return
      if (preview && preview.sha256 !== next.sha256 && offset !== 0) {
        setError('文档内容已改变，请重新打开后阅读。'); return
      }
      setPreview(next); setOffsets(trail)
    } catch (e) { if (token === request.current) setError(e instanceof Error ? e.message : '文档读取失败') }
    finally { if (token === request.current) setBusy(false) }
  }
  useEffect(() => {
    dialog.current?.showModal(); void load(0, [0])
    return () => { request.current++ }
  }, [document.id])
  return <dialog ref={dialog} className="document-reader" onClose={onClose} onCancel={onClose} aria-label="阅读文档">
    <header><h2>{document.fileName}</h2><button className="ghost" autoFocus aria-label="关闭文档阅读" onClick={() => dialog.current?.close()}>关闭</button></header>
    <p className="hint">显示原文中提取的文字；不重建索引、不调用模型。</p>
    {error && <p className="error-text" role="alert">{error}</p>}
    <div className="reader-toolbar"><button className="secondary" disabled={busy || offsets.length < 2} onClick={() => void load(offsets.at(-2)!, offsets.slice(0,-1))}>上一页</button><span>{preview ? `${preview.offset + (preview.content ? 1 : 0)}–${preview.nextOffset} / ${preview.totalChars} 字符` : '读取中…'}</span><button className="secondary" disabled={busy || !preview || preview.nextOffset >= preview.totalChars} onClick={() => void load(preview!.nextOffset, [...offsets,preview!.nextOffset])}>下一页</button>{preview && <CopyButton text={preview.content} label="复制本页" />}</div>
    {busy ? <p role="status">正在读取文档…</p> : preview && <pre key={preview.offset} className="document-text">{preview.content || '文档没有可提取的文字'}</pre>}
    {error && <button className="secondary" disabled={busy} onClick={() => void load(offsets.at(-1) ?? 0, offsets)}>重新读取本页</button>}
  </dialog>
}
