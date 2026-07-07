import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Send, Square } from 'lucide-react'
import { useAiKeys } from '@/hooks/useAiKeys'
import { useAiChatStore } from '@/stores/aiChatStore'
import { streamChat } from '@/lib/sse'
import { ApiError } from '@/lib/http'
import { toast } from 'sonner'
import { ButtonIcon } from './ButtonIcon'
import { EmptyState } from './EmptyState'
import { LoadingState } from './feedback/LoadingState'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'

const SUGGESTIONS = [
  { text: '帮我加追踪止损 5%', autoSend: false },
  { text: '解释这段策略代码', autoSend: true },
  { text: '优化最大回撤', autoSend: true },
  { text: '加仓位管理', autoSend: true },
]

/**
 * AISidebar — AI 编程助手侧栏(spec §4.5)。
 *
 * - LLM key picker(shadcn Select,useAiKeys);为空显示配置提示
 * - provider 标签(Badge,如 OPENAI;LlmApiKeyView 无 model 字段,用 provider 替)
 * - 4 提示卡片(快捷输入,前 1 个不自动发送,后 3 个自动发送)
 * - 多轮消息管理(aiChatStore);strategyId 从 URL :id 取
 * - SSE streamChat 打字机 + Stop(AbortController)
 * - 401 不重放:streamChat 抛 ApiError(1001) → toast "登录已过期"
 */
export function AISidebar({ strategyId: strategyIdProp }: { strategyId?: number } = {}) {
  const { id: strategyIdParam } = useParams<{ id: string }>()
  const strategyId =
    strategyIdProp ?? (strategyIdParam ? parseInt(strategyIdParam, 10) : null)
  const { data: keys, isLoading: keysLoading } = useAiKeys()
  const { messages, streaming, llmKeyId, setLlmKeyId, setStreaming } =
    useAiChatStore()
  const [input, setInput] = useState('')
  const abortRef = useRef<AbortController | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  const activeKey = keys?.find((k) => k.id === llmKeyId)

  // 选第一个 key 作为默认(首次加载有 keys 时)
  useEffect(() => {
    if (keys && keys.length > 0 && llmKeyId === null) {
      setLlmKeyId(keys[0].id)
    }
  }, [keys, llmKeyId, setLlmKeyId])

  // 消息列表自动滚到底
  useEffect(() => {
    scrollRef.current?.scrollTo?.({ top: scrollRef.current.scrollHeight })
  }, [messages])

  const send = async (textOverride?: string) => {
    const text = (textOverride ?? input).trim()
    const store = useAiChatStore.getState()
    if (!text || store.streaming || !store.llmKeyId || !strategyId) return

    store.addUserMessage(text)
    const messagesToSend = store.messages // 快照(含新 user,不含 assistant)
    store.startAssistant()
    setInput('')

    const controller = new AbortController()
    abortRef.current = controller

    try {
      await streamChat(
        '/api/v1/ai/chat',
        {
          llmKeyId: store.llmKeyId,
          messages: messagesToSend,
          strategyId,
        },
        controller.signal,
        {
          onChunk: (data) => useAiChatStore.getState().appendToLastAssistant(data),
          onError: (data) => {
            toast.error(data)
            useAiChatStore.getState().markLastAssistantError(data)
          },
          onClose: () => useAiChatStore.getState().setStreaming(false),
        },
      )
    } catch (e) {
      if (e instanceof ApiError && e.isUnauthorized) {
        toast.error('登录已过期,请重新登录后重试')
      } else if (e instanceof ApiError) {
        toast.error(e.message)
      } else {
        toast.error(e instanceof Error ? e.message : 'AI 请求失败')
      }
      useAiChatStore.getState().setStreaming(false)
    }
  }

  const stop = () => {
    abortRef.current?.abort()
    setStreaming(false)
  }

  const applySuggestion = (s: { text: string; autoSend: boolean }) => {
    setInput(s.text)
    if (s.autoSend) send(s.text)
  }

  return (
    <aside className="flex h-full w-full flex-col border-l border-border bg-surface-card">
      <header className="border-b border-border-soft px-lg py-md">
        <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">AI</p>
        <h2 className="mt-sm font-display text-h3">编程助手</h2>
      </header>

      {/* LLM key picker + provider 标签 */}
      <div className="px-lg py-md">
        {keysLoading ? (
          <LoadingState label="加载 keys…" />
        ) : keys && keys.length > 0 ? (
          <div className="flex items-center gap-sm">
            <Select
              value={llmKeyId !== null ? String(llmKeyId) : undefined}
              onValueChange={(v) => setLlmKeyId(parseInt(v, 10))}
            >
              <SelectTrigger className="flex-1" aria-label="选择 LLM key">
                <SelectValue placeholder="选 LLM key" />
              </SelectTrigger>
              <SelectContent>
                {keys.map((k) => (
                  <SelectItem key={k.id} value={String(k.id)}>
                    {k.label} ({k.apiKeyMasked})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {activeKey && <Badge variant="secondary">{activeKey.provider}</Badge>}
          </div>
        ) : (
          <EmptyState
            title="未配置 LLM key"
            description="请在设置页配置至少一个 LLM API key 后使用 AI 助手"
          />
        )}
      </div>

      {/* 提示卡片(快捷输入) */}
      {keys && keys.length > 0 && (
        <div className="flex flex-wrap gap-xs px-lg pb-sm">
          {SUGGESTIONS.map((s) => (
            <button
              key={s.text}
              type="button"
              onClick={() => applySuggestion(s)}
              className="rounded-full border border-border px-md py-xs text-body-sm text-text-secondary hover:bg-surface-hover hover:text-text-primary"
            >
              {s.text}
            </button>
          ))}
        </div>
      )}

      {/* 消息列表 */}
      <div ref={scrollRef} className="flex-1 space-y-md overflow-y-auto px-lg py-md">
        {messages.length === 0 && (
          <EmptyState
            illustration={<span className="text-h2">💬</span>}
            title="开始对话"
            description="向 AI 提问策略代码、调试、优化建议等"
          />
        )}
        {messages.map((m, i) => (
          <div
            key={i}
            className={
              m.role === 'user'
                ? 'ml-auto max-w-[80%] rounded-lg bg-primary px-md py-sm text-on-primary'
                : 'mr-auto max-w-[80%] rounded-lg bg-surface-card-2 px-md py-sm text-text-primary'
            }
          >
            <p className="whitespace-pre-wrap font-body text-body-sm">{m.content || '…'}</p>
          </div>
        ))}
      </div>

      {/* 输入区 */}
      <div className="border-t border-border-soft p-md">
        <div className="flex items-end gap-sm">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                send()
              }
            }}
            placeholder={strategyId ? '提问…(Enter 发送,Shift+Enter 换行)' : '请先打开一个策略'}
            disabled={!strategyId || !llmKeyId}
            rows={2}
            className="flex-1 resize-none rounded-md border border-border bg-surface-input px-md py-2 font-body text-body-sm text-text-primary placeholder:text-text-muted focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft disabled:opacity-50"
          />
          {streaming ? (
            <ButtonIcon label="停止生成" variant="copper" size="md" onClick={stop}>
              <Square className="size-4" aria-hidden />
            </ButtonIcon>
          ) : (
            <ButtonIcon
              label="发送"
              variant="copper"
              size="md"
              onClick={() => send()}
              disabled={!input.trim() || !strategyId || !llmKeyId}
            >
              <Send className="size-4" aria-hidden />
            </ButtonIcon>
          )}
        </div>
      </div>
    </aside>
  )
}
