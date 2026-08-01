import { memo } from 'react'
import { RefreshCw } from 'lucide-react'
import { MarkdownText } from './MarkdownText'
import { Button } from '@/components/ui/button'
import type { StoreMessage } from '@/hooks/useAssistantChat'

/**
 * MessageItem — 单条会话消息渲染(自建,弃 assistant-ui MessagePrimitive)。
 *
 * 视觉:无气泡(2026 主流,ChatGPT/Claude.ai/Cursor 同款)。
 *  - user:浅底 surface-card-2 rounded-xl 右对齐,max-w-[85%](气泡感但不 IM 化)
 *  - assistant:全宽,AI 方块头像 + MarkdownText 渲染,流式末尾 ▍ 光标
 *
 * React.memo by 浅比较(id/role/content/error/isStreaming) —— 流式时只重渲当前
 * streaming 那条,历史消息 memo 跳过,解"消息多了卡住"(症状 2)。
 *
 * streaming 三态:
 *  - 有 content:MarkdownText + 末尾 ▍(motion-safe pulse;prefers-reduced-motion 静态)
 *  - 空 content(首 chunk 未到):pulse dot + "正在思考…"(避免空白)
 *  - error:destructive 内联提示 + 重试按钮(替代 content)
 */
interface MessageItemProps {
  message: StoreMessage
  isStreaming?: boolean
  onRetry?: () => void
}

function MessageItemBase({ message, isStreaming = false, onRetry }: MessageItemProps) {
  if (message.role === 'user') {
    return (
      <div className="flex flex-col items-end gap-xxs">
        <span className="text-label-caps text-text-muted">{message.ts}</span>
        <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-xl bg-surface-card-2 px-sm py-xs text-body-sm text-text-primary">
          {message.content}
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-xxs">
      <span className="text-label-caps text-text-muted">AI · {message.ts}</span>
      <div className="flex gap-xs">
        <div
          className="flex size-5 shrink-0 items-center justify-center rounded-md bg-accent text-caption font-bold text-on-accent"
          aria-hidden
        >
          AI
        </div>
        <div className="min-w-0 flex-1">
          {message.error ? (
            <div className="rounded-md border border-destructive/40 bg-destructive/5 px-sm py-xs">
              <p className="text-body-sm text-destructive">{message.error}</p>
              {onRetry && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={onRetry}
                  className="mt-xxs h-7 gap-xxs text-caption text-text-secondary hover:text-text-primary"
                >
                  <RefreshCw className="size-3" aria-hidden />
                  重试
                </Button>
              )}
            </div>
          ) : message.content ? (
            <div className="flex items-start gap-xxs">
              <div className="min-w-0 flex-1">
                <MarkdownText text={message.content} />
              </div>
              {isStreaming && (
                <span
                  className="mt-xxs inline-block text-accent motion-safe:animate-pulse"
                  aria-label="AI 正在生成"
                >
                  ▍
                </span>
              )}
            </div>
          ) : isStreaming ? (
            <div className="flex items-center gap-xxs text-body-sm text-text-muted">
              <span className="size-1.5 motion-safe:animate-pulse rounded-full bg-accent" aria-hidden />
              正在思考…
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

export const MessageItem = memo(MessageItemBase)
