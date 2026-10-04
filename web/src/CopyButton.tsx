import { useEffect, useState } from 'react'

export function CopyButton({ text, label = '复制', className = '' }: { text: string; label?: string; className?: string }) {
  const [state, setState] = useState('')
  useEffect(() => { setState('') }, [text])
  useEffect(() => {
    if (!state) return
    const timer = window.setTimeout(() => setState(''), 2200)
    return () => window.clearTimeout(timer)
  }, [state])
  return <button type="button" className={`ghost copy-button ${className}`} disabled={!text} title={state === '复制失败' ? '可选中文字手动复制' : label} onClick={async () => {
    try { await navigator.clipboard.writeText(text); setState('已复制') } catch { setState('复制失败') }
  }} aria-label={label}><span role="status">{state || label}</span></button>
}
