import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { followingLatest } from './listTools'

export function MessageFeed({ children, className, conversationKey, count, lastContent, busy }: {
  children: ReactNode; className: string; conversationKey: string; count: number; lastContent: string; busy: boolean
}) {
  const root = useRef<HTMLDivElement>(null)
  const follow = useRef(true)
  const [following, setFollowing] = useState(true)
  useLayoutEffect(() => { follow.current = true; setFollowing(true) }, [conversationKey])
  useLayoutEffect(() => {
    if (follow.current && root.current) root.current.scrollTop = root.current.scrollHeight
  }, [conversationKey,count,lastContent])
  return <div className="message-feed-shell"><div className={className} ref={root} onScroll={e => {
    const element = e.currentTarget
    follow.current = followingLatest(element.scrollTop,element.clientHeight,element.scrollHeight)
    setFollowing(follow.current)
  }}>{children}</div><div className="feed-actions">{!following && busy && <span role="status">有新内容；阅读位置保持不变</span>}<button type="button" className="ghost" disabled={!count} onClick={() => {
    follow.current = true; setFollowing(true)
    if (root.current) root.current.scrollTop = root.current.scrollHeight
  }}>最新消息 ↓</button></div></div>
}
