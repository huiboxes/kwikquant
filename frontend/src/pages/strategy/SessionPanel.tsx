import { useState } from 'react'
import { Send, Maximize2, Minimize2 } from 'lucide-react'
import { ChatThread } from '@/components/chat/ChatThread'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useAssistantChat, type EditorCodeRef, type CodeSource } from '@/hooks/useAssistantChat'
import { useLlmKeys } from '@/hooks/useSettings'
import type { StrategyDetailDto } from '@/api/strategy'

/**
 * SessionPanel — 右侧"会话" tab:AI 策略编码助手(自建 ChatThread + Composer)。
 *
 * 弃 assistant-ui ExternalStoreRuntime + Thread(legacy 路径 + 卡住根因 + Composer React19 bug)。
 * useAssistantChat 的 messages state 直连 ChatThread 渲染,无 runtime 中间层 →
 * 乐观渲染立即反映到 DOM(解"发消息后不立即显示")+ rAF 批处理解"消息多了卡"。
 *
 * 自建 Composer(Textarea + Send/Stop)保留。model + 代码版本切换器保留。全屏 icon 保留。
 */
interface SessionPanelProps {
  strategy: StrategyDetailDto | null
  version: number | null
  /** 编辑器实时 code ref(父组件编辑器 onChange 写 ref.current,不 setState)。 */
  editorCodeRef?: EditorCodeRef
  /** 全屏态(会话窗口铺满主区,代码编辑器让出空间)。 */
  fullscreen?: boolean
  /** 切换全屏(Bug3:会话窗口全屏 icon)。 */
  onToggleFullscreen?: () => void
}

/** 建议问题列表(空会话时 Welcome 显示,chips 点击直接 onRun 发送)。 */
const SUGGESTIONS = [
  '加一个 ADX 过滤震荡市',
  '改成以波段低点设止损',
  '帮我加上资金费率过滤',
  '把止损改为追踪止损',
]

export function SessionPanel({
  strategy,
  version,
  editorCodeRef,
  fullscreen,
  onToggleFullscreen,
}: SessionPanelProps) {
  const { data: llmKeys } = useLlmKeys()
  const activeKey = llmKeys && llmKeys.length > 0 ? llmKeys[0] : null
  const llmKeyId = activeKey?.id ?? null
  const availableModels = activeKey?.availableModels ?? []

  // fallback 空 ref(editorCodeRef 未接线时);useState lazy init 稳定对象(不随 render 变)
  const [fallbackCodeRef] = useState<EditorCodeRef>(() => ({ current: null }))
  const codeRef = editorCodeRef ?? fallbackCodeRef

  const { messages, isRunning, model, setModel, codeSource, setCodeSource, onRun, onCancel, retryLast } =
    useAssistantChat(strategy?.id ?? null, availableModels, codeRef)

  // 自建 Composer draft
  const [draft, setDraft] = useState('')

  const handleSend = () => {
    const trimmed = draft.trim()
    if (!trimmed || isRunning) return
    onRun(trimmed, llmKeyId)
    setDraft('')
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
          <div className="truncate text-caption-xs text-text-muted">
            已附带当前策略 · {strategy?.name ?? '…'} · {version ? `v${version}` : '未发布'}
          </div>
        </div>
        {onToggleFullscreen && (
          <button
            type="button"
            onClick={onToggleFullscreen}
            title={fullscreen ? '退出全屏' : '全屏会话(占用代码空间)'}
            aria-label={fullscreen ? '退出全屏' : '全屏会话'}
            className="flex size-6 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-3 hover:text-text-primary"
          >
            {fullscreen ? <Minimize2 className="size-3.5" aria-hidden /> : <Maximize2 className="size-3.5" aria-hidden />}
          </button>
        )}
      </div>

      {/* ChatThread(自建,替 assistant-ui Thread):消息列表 + 空态 Welcome + sticky-bottom */}
      <ChatThread
        messages={messages}
        isRunning={isRunning}
        onRetry={retryLast}
        suggestions={SUGGESTIONS}
        onSuggestion={(text) => onRun(text, llmKeyId)}
      />

      {/* model + 代码版本切换(Composer 上方一行;版本=策略代码来源 editor/draft/published) */}
      <div className="flex items-center gap-2 border-t border-border-soft px-3.5 pt-2">
        <Select
          value={model}
          onValueChange={(v) => {
            setModel(v)
            if (strategy?.id != null) {
              localStorage.setItem(`ai-chat-model-${strategy.id}`, v)
            }
          }}
        >
          <SelectTrigger className="h-7 w-48 text-caption-sm">
            <SelectValue
              placeholder={availableModels.length === 0 ? '去设置页配模型' : '选择模型'}
            />
          </SelectTrigger>
          <SelectContent>
            {availableModels.map((m) => (
              <SelectItem key={m} value={m} className="text-caption-sm">
                {m}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={codeSource}
          onValueChange={(v) => setCodeSource(v as CodeSource)}
        >
          <SelectTrigger className="h-7 w-28 text-caption-sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="EDITOR" className="text-caption-sm">编辑器</SelectItem>
            <SelectItem value="DRAFT" className="text-caption-sm">草稿</SelectItem>
            <SelectItem value="PUBLISHED" className="text-caption-sm">已发布</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* 自建 Composer(Textarea + Send/Stop) */}
      <div className="flex items-end gap-2 border-t border-border-soft px-3.5 py-2.5">
        <Textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          placeholder={isRunning ? 'AI 生成中…' : '请输入(Enter 发送, Shift+Enter 换行)'}
          disabled={isRunning}
          className="min-h-[40px] max-h-[120px] flex-1 resize-none bg-surface-card-2 text-caption"
        />
        {isRunning ? (
          <Button onClick={onCancel} size="sm" variant="destructive">
            停止
          </Button>
        ) : (
          <Button onClick={handleSend} disabled={!draft.trim()} size="sm">
            <Send className="size-3.5" aria-hidden />
            发送
          </Button>
        )}
      </div>
    </div>
  )
}
