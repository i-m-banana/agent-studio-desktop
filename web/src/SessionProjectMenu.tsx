import { useEffect, useRef, useState, type ReactNode } from 'react'

export function SessionProjectMenu({ label, busy, children, openRequest=0, onOpened }: { label: string; busy: boolean; children: ReactNode; openRequest?:number; onOpened?:()=>void }) {
  const [open, setOpen] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { if(openRequest>0 && !busy) {setOpen(true);onOpened?.()} },[openRequest,busy])
  useEffect(() => { if (open) dialog.current?.showModal() }, [open])
  useEffect(() => { if (busy) dialog.current?.close() }, [busy])
  return <>
    <button className="secondary session-project-button" disabled={busy} onClick={() => setOpen(true)} title="选择会话项目，或运行本地测试与构建">{label}</button>
    {open && <dialog ref={dialog} className="session-project-dialog" onClose={() => setOpen(false)}>
      <header><div><h2>会话项目</h2><p>设置本次会话的工作区，或请求本地验证。</p></div><button className="ghost" aria-label="关闭会话项目" autoFocus onClick={() => dialog.current?.close()}>关闭</button></header>
      {children}
    </dialog>}
  </>
}
