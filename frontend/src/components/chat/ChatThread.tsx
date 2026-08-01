import { useEffect, useRef, useState } from 'react'
import { ArrowDown } from 'lucide-react'
import { MessageItem } from './MessageItem'
import type { StoreMessage } from '@/hooks/useAssistantChat'

/**
 * ChatThread — 自建消息列表(弃 assistant-ui ThreadPrimitive)。
 *
 * 原生 div overflow-y-auto + onScroll,无虚拟化(YAGNI;React.memo 隔离历史消息,
 * 流式只重渲 last assistant,几百条够用;预留接口真卡了再加 react-virtuoso)。
 *
 * sticky-bottom(调研:Cursor/Claude Code desktop 因强制下拉被投诉,Perplexity 做对):
 *  - 距底 ≤100px:流式自动跟随(messages 变化 → useEffect 滚到底)
 *  - 距底 >100px(用户上滚查看历史):锁住不跟随 + 底部浮「↓ 新消息」按钮,click 回底
 *
 * 空态:messages=[] 渲染 Welcome(中文 h1 + suggestions chips),替代 assistant-ui
 * ThreadWelcome 英文 "How can I help you today?"。
 */
interface ChatThreadProps {
  messages: StoreMessage[]
  isRunning: boolean
  onRetry?: () => void
  suggestions?: string[]
  onSuggestion?: (text: string) => void
}

/** 距底阈值(px),≤ 此值视为"在底部",跟随流式滚动。 */
const STICKY_THRESHOLD = 100

export function ChatThread({
  messages,
  isRunning,
  onRetry,
  suggestions,
  onSuggestion,
}: ChatThreadProps) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const [isAtBottom, setIsAtBottom] = useState(true)

  const computeAtBottom = (): boolean => {
    const el = scrollRef.current
    if (!el) return true
    return el.scrollHeight - el.scrollTop - el.clientHeight < STICKY_THRESHOLD
  }

  const onScroll = () => {
    setIsAtBottom(computeAtBottom())
  }

  const scrollToBottom = () => {
    const el = scrollRef.current
    if (el) {
      el.scrollTop = el.scrollHeight
    }
    setIsAtBottom(true)
  }

  // 自动跟随:isAtBottom 时 messages 变化滚到底(rAF flush 每帧 messages 变 → 跟随流式)
  useEffect(() => {
    if (isAtBottom && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages, isAtBottom])

  const isEmpty = messages.length === 0
  const lastIndex = messages.length - 1

  return (
    <div ref={scrollRef} onScroll={onScroll} className="relative flex-1 overflow-y-auto">
      {isEmpty ? (
        <div className="flex h-full min-h-0 flex-col items-center justify-center px-md text-center">
          <h2 className="text-lg font-semibold text-text-primary">
            我可以帮你改进或调试策略
          </h2>
          <p className="mt-xxs text-caption text-text-muted">
            试试这些问题,或直接描述你的需求
          </p>
          {suggestions && suggestions.length > 0 && (
            <div className="mt-md flex flex-wrap justify-center gap-xxs">
              {suggestions.map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => onSuggestion?.(s)}
                  className="rounded-full border border-border-soft bg-surface-card-2 px-sm py-xxs text-caption text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary"
                >
                  {s}
                </button>
              ))}
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-col gap-md p-sm">
          {messages.map((m, i) => (
            <MessageItem
              key={m.id}
              message={m}
              isStreaming={isRunning && i === lastIndex && m.role === 'assistant'}
              onRetry={onRetry}
            />
          ))}
        </div>
      )}

      {!isAtBottom && !isEmpty && (
        <button
          type="button"
          onClick={scrollToBottom}
          aria-label="滚动到最新消息"
          className="absolute bottom-sm left-1/2 flex -translate-x-1/2 items-center gap-xxs rounded-full border border-border-soft bg-surface-card px-sm py-xxs text-caption text-text-secondary shadow-pop transition-colors hover:bg-surface-hover hover:text-text-primary"
        >
          <ArrowDown className="size-3" aria-hidden />
          新消息
        </button>
      )}
    </div>
  )
}
