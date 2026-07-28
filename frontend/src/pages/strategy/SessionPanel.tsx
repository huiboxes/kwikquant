import { useEffect, useRef } from 'react'
import { Send } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { useLlmKeys } from '@/hooks/useSettings'
import { useStreamChat } from '@/hooks/useStreamChat'
import type { StrategyDetailDto } from '@/api/strategy'

/**
 * SessionPanel — 右侧"会话" tab:AI 策略编码助手(SSE 流式)。
 *
 * 从原 AiFab Sheet 抽取(用户要求右侧默认会话窗口,故对话从 FAB-Sheet 迁到右侧常驻 tab)。
 * useStreamChat 在此唯一调用(两处各调 hook 会各自独立 state 不同步,故 AiFab 不再自带对话)。
 */
interface SessionPanelProps {
  strategy: StrategyDetailDto | null
  version: number | null
}

/** 建议问题列表(原型 SUGGESTIONS)。 */
const SUGGESTIONS = [
  '加一个 ADX 过滤震荡市',
  '改成 swing low 止损',
  '帮我加上资金费率过滤',
  '把 stop_loss 改成 trailing',
]

/** 把 AI 文本按 ``` 代码块分段渲染。 */
function renderChatContent(text: string) {
  const parts = text.split('```')
  return parts.map((seg, idx) => {
    if (idx % 2 === 1) {
      return (
        <pre
          key={idx}
          className="my-1.5 overflow-auto rounded-md bg-surface-card-2 p-2.5 font-mono text-[11px] text-text-primary"
        >
          {seg}
        </pre>
      )
    }
    return (
      <span key={idx} className="whitespace-pre-wrap break-words">
        {seg}
      </span>
    )
  })
}

export function SessionPanel({ strategy, version }: SessionPanelProps) {
  const { data: llmKeys } = useLlmKeys()
  const llmKeyId = llmKeys && llmKeys.length > 0 ? llmKeys[0].id : null
  const { messages, streaming, streamText, draft, setDraft, model, setModel, send } =
    useStreamChat(strategy?.id ?? null)
  const endRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)

  // 消息更新时自动滚动到底部
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages, streamText, streaming])

  const handleSend = () => {
    if (!strategy) return
    send(draft, llmKeyId)
  }

  return (
    <div className="m-xxs flex flex-1 flex-col overflow-hidden rounded-xl bg-surface-card">
      {/* Header */}
      <div className="flex items-center gap-2 border-b border-border-soft px-sm py-xs">
        <div className="flex size-5 items-center justify-center rounded-md bg-accent text-caption font-bold text-on-accent">
          AI
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-body-sm font-semibold text-text-primary">策略编码助手</div>
          <div className="truncate text-[10px] text-text-muted">
            已注入上下文 · {strategy?.name ?? '…'} · {version ? `v${version}` : '未发布'}
          </div>
        </div>
        <input
          type="text"
          value={model}
          onChange={(e) => setModel(e.target.value)}
          placeholder="模型(留空用key默认)"
          aria-label="会话模型"
          className="w-28 shrink-0 rounded-md border border-border-soft bg-surface-card-2 px-2 py-1 text-[11px] text-text-primary placeholder:text-text-muted focus:outline-none focus:ring-1 focus:ring-accent"
        />
      </div>

      {/* 消息区 */}
      <div className="flex flex-1 flex-col gap-3.5 overflow-auto px-3.5 py-3">
        {messages.map((m, i) => {
          const isUser = m.role === 'user'
          return (
            <div key={i} className={`flex gap-2 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}>
              <div
                className={`flex size-6 shrink-0 items-center justify-center rounded-sm text-[11px] font-bold ${
                  isUser ? 'bg-surface-3 text-text-primary' : 'bg-accent text-on-accent'
                }`}
              >
                {isUser ? '你' : 'AI'}
              </div>
              <div className="max-w-[82%]">
                <div className={`mb-0.5 text-[10px] text-text-muted ${isUser ? 'text-right' : 'text-left'}`}>
                  {m.ts}
                </div>
                <div
                  className={`rounded-lg border border-border-soft px-3 py-2 text-caption leading-relaxed text-text-primary ${
                    isUser ? 'bg-surface-card-2' : 'bg-accent-soft'
                  }`}
                  style={{ borderTopRightRadius: isUser ? 2 : 10, borderTopLeftRadius: isUser ? 10 : 2 }}
                >
                  {renderChatContent(m.content)}
                </div>
              </div>
            </div>
          )
        })}
        {/* 流式输出中 */}
        {streaming && (
          <div className="flex gap-2">
            <div
              className="flex size-6 shrink-0 items-center justify-center rounded-sm bg-accent text-[11px] font-bold text-on-accent"
            >
              AI
            </div>
            <div className="flex-1">
              <div className="mb-0.5 text-[10px] text-text-muted">正在生成…</div>
              <div className="kq-stream-cursor whitespace-pre-wrap text-caption leading-relaxed text-text-primary">
                {streamText}
              </div>
            </div>
          </div>
        )}
        <div ref={endRef} />
      </div>

      {/* 建议 chips(非流式时显示) */}
      {!streaming && (
        <div className="flex flex-wrap gap-1.5 px-3.5">
          {SUGGESTIONS.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => setDraft(s)}
              className="rounded-full border border-border-soft bg-surface-card-2 px-2.5 py-1 text-[11px] text-text-secondary transition hover:bg-surface-3"
            >
              {s}
            </button>
          ))}
        </div>
      )}

      {/* 输入区 */}
      <div className="flex items-end gap-2 border-t border-border-soft px-3.5 py-2.5">
        <Textarea
          ref={inputRef}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          placeholder="问 AI 关于当前策略的问题…(Enter 发送,Shift+Enter 换行)"
          className="min-h-[40px] max-h-[120px] flex-1 resize-none bg-surface-card-2 text-caption"
        />
        <Button onClick={handleSend} disabled={streaming || !draft.trim()} size="sm">
          <Send className="size-3.5" aria-hidden />
          发送
        </Button>
      </div>
    </div>
  )
}
