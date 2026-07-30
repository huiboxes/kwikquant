import { useEffect, useRef, useState } from 'react'
import { Send, Plus } from 'lucide-react'
import { AddLlmKeyDialog } from '@/components/AddLlmKeyDialog'
import type { LlmApiKeyView } from '@/api/ai'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
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
  '改成以波段低点设止损',
  '帮我加上资金费率过滤',
  '把止损改为追踪止损',
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
  const [activeKeyId, setActiveKeyId] = useState<number | null>(null)
  // 初始化:llmKeys 加载 + strategy 已知 → localStorage 读 key 或 llmKeys[0]
  // render 内条件 setState(React 官方模式;activeKeyId 设后 !=null 不再触发,防 infinite)
  if (activeKeyId == null && llmKeys && llmKeys.length > 0 && strategy?.id != null) {
    const storedId = localStorage.getItem(`ai-chat-key-${strategy.id}`)
    const id = storedId ? parseInt(storedId, 10) : llmKeys[0].id
    setActiveKeyId(llmKeys.some((k) => k.id === id) ? id : llmKeys[0].id)
  }
  const activeKey =
    activeKeyId != null && llmKeys
      ? llmKeys.find((k) => k.id === activeKeyId) ?? llmKeys[0] ?? null
      : null
  const llmKeyId = activeKey?.id ?? null
  const availableModels = activeKey?.availableModels ?? []
  const { messages, streaming, streamText, draft, setDraft, model, setModel, send } =
    useStreamChat(strategy?.id ?? null, availableModels)
  const endRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)
  const [showAddLlm, setShowAddLlm] = useState(false)
  const [editingKey, setEditingKey] = useState<LlmApiKeyView | null>(null)

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
            已附带当前策略 · {strategy?.name ?? '…'} · {version ? `v${version}` : '未发布'}
          </div>
        </div>
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

      {/* 密钥/模型 切换(composer 上方一行;密钥 Select 切 key→模型 Select 显该 key models;[+] compact 管理当前 key) */}
      <div className="flex items-center gap-2 border-t border-border-soft px-3.5 pt-2">
        {llmKeys && llmKeys.length > 0 && activeKey ? (
          <>
            {availableModels.length === 0 ? (
              <Button
                variant="outline"
                size="sm"
                className="h-7 gap-1 px-2.5 text-[11px]"
                onClick={() => {
                  setEditingKey(activeKey)
                  setShowAddLlm(true)
                }}
              >
                <Plus className="size-3" aria-hidden />
                配模型
              </Button>
            ) : (
              <div className="flex items-center gap-1.5">
                <Select
                  value={model}
                  onValueChange={(v) => {
                    setModel(v)
                    if (strategy?.id != null) {
                      localStorage.setItem(`ai-chat-model-${strategy.id}`, v)
                    }
                  }}
                >
                  <SelectTrigger className="h-7 w-44 text-[11px]">
                    <SelectValue placeholder="选择模型" />
                  </SelectTrigger>
                  <SelectContent>
                    {availableModels.map((m) => (
                      <SelectItem key={m} value={m} className="text-[11px]">
                        {m}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2"
                  onClick={() => {
                    setEditingKey(activeKey)
                    setShowAddLlm(true)
                  }}
                  aria-label="管理模型(添加/删除)"
                  title="管理模型(添加/删除)"
                >
                  <Plus className="size-3" aria-hidden />
                </Button>
              </div>
            )}
          </>
        ) : (
          <Button
            variant="outline"
            size="sm"
            className="h-7 gap-1 px-2.5 text-[11px]"
            onClick={() => {
              setEditingKey(null)
              setShowAddLlm(true)
            }}
          >
            <Plus className="size-3" aria-hidden />
            添加密钥
          </Button>
        )}
      </div>

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
          placeholder="请输入(Enter 发送, Shift+Enter换行)"
          className="min-h-[40px] max-h-[120px] flex-1 resize-none bg-surface-card-2 text-caption"
        />
        <Button onClick={handleSend} disabled={streaming || !draft.trim()} size="sm">
          <Send className="size-3.5" aria-hidden />
          发送
        </Button>
      </div>

      {/* 会话内快速配置 modal(空态点「+ 配置」打开;复用 AddLlmKeyDialog 共享组件,跟设置页一致。
          保存成功后 react-query invalidate aiKeys → useLlmKeys refetch → availableModels 更新 → Select 显示) */}
      {showAddLlm && (
        <AddLlmKeyDialog
          key={editingKey?.id ?? 'new'}
          open={showAddLlm}
          onOpenChange={(v) => {
            setShowAddLlm(v)
            if (!v) setEditingKey(null)
          }}
          editingKey={editingKey}
          compact={!!editingKey}
          llmKeys={llmKeys ?? []}
          onKeyChange={(id) => {
            const k = llmKeys?.find((x) => x.id === id)
            if (k) {
              // 联动:切管理 key → 会话 activeKey 跟切(key prop remount → lazy init 预填新 key models)
              setActiveKeyId(id)
              setEditingKey(k)
              if (strategy?.id != null) localStorage.setItem(`ai-chat-key-${strategy.id}`, String(id))
            }
          }}
        />
      )}
    </div>
  )
}
