import { useEffect, useState } from 'react'

type BackendState = 'checking' | 'online' | 'offline'

function App() {
  const [backendState, setBackendState] = useState<BackendState>('checking')

  useEffect(() => {
    const controller = new AbortController()
    fetch('/api/system/status', { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error('Backend unavailable')
        setBackendState('online')
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        setBackendState('offline')
      })
    return () => controller.abort()
  }, [])

  const statusText = {
    checking: '正在检查后端',
    online: '后端已连接',
    offline: '后端未启动',
  }[backendState]

  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">LOCAL-FIRST AGENT BUILDER</p>
        <h1>Agent Studio Desktop</h1>
        <p className="intro">
          在本机组合模型、提示词、知识库和工具，并让每一步运行都可观察、可确认、可追溯。
        </p>
        <div className={`status status--${backendState}`}>
          <span className="status__dot" aria-hidden="true" />
          {statusText}
        </div>
      </section>

      <section className="roadmap" aria-label="首版建设范围">
        <article>
          <span>01</span>
          <h2>配置</h2>
          <p>模型、提示词与 Agent 版本</p>
        </article>
        <article>
          <span>02</span>
          <h2>运行</h2>
          <p>流式对话与明确的 ReAct 主链</p>
        </article>
        <article>
          <span>03</span>
          <h2>验证</h2>
          <p>来源、步骤、审批和审计证据</p>
        </article>
      </section>
    </main>
  )
}

export default App

