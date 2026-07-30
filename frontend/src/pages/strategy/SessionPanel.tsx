import { useCallback, useState } from 'react'
import { AssistantRuntimeProvider, useExternalStoreRuntime } from '@assistant-ui/react'
import type { ThreadMessageLike, AppendMessage } from '@assistant-ui/react'
import { Thread } from '@/components/assistant-ui/thread'
import { TooltipProvider } from '@/components/ui/tooltip'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useAssistantChat, type StoreMessage, type EditorCodeRef, type CodeSource } from '@/hooks/useAssistantChat'
import { useLlmKeys } from '@/hooks/useSettings'
import type { StrategyDetailDto } from '@/api/strategy'

/**
 * SessionPanel — 右侧"会话" tab:AI 策略编码助手(assistant-ui Thread + Composer)。
 *
 * 用 useAssistantChat(ExternalStoreRuntime adapter)管理 SSE 流式 state,
 * useExternalStoreRuntime 组装 runtime,Thread 渲染消息列表 + markdown + 流式增量,
 * Composer 输入 + 发送 + isRunning 时变停止(cancel→onCancel→abortRef)。
 *
 * 自定义 Welcome(中文 h1 + SUGGESTIONS chips)替代默认英文 ThreadWelcome,
 * chips 点击直接 onRun(发送建议)。model 切换器 Composer 上方(Task 5 加版本切换器并列)。
 */
interface SessionPanelProps {
  strategy: StrategyDetailDto | null
  version: number | null
  /** 编辑器实时 code ref(父组件编辑器 onChange 写 ref.current,不 setState)。Task 5 接线 RightPanel。 */
  editorCodeRef?: EditorCodeRef
}

/** 建议问题列表(原型 SUGGESTIONS,空会话时 Welcome 显示)。 */
const SUGGESTIONS = [
  '加一个 ADX 过滤震荡市',
  '改成以波段低点设止损',
  '帮我加上资金费率过滤',
  '把止损改为追踪止损',
]

/** store 消息 → assistant-ui ThreadMessageLike(role + content text part)。 */
function convertMessage(m: StoreMessage): ThreadMessageLike {
  return {
    role: m.role,
    content: [{ type: 'text', text: m.content }],
  }
}

export function SessionPanel({ strategy, version, editorCodeRef }: SessionPanelProps) {
  const { data: llmKeys } = useLlmKeys()
  const activeKey = llmKeys && llmKeys.length > 0 ? llmKeys[0] : null
  const llmKeyId = activeKey?.id ?? null
  const availableModels = activeKey?.availableModels ?? []

  // Task 5 父组件传 editorCodeRef;未接线时 fallback 空 ref(editor 模式 sourceCode=null,后端 @AssertTrue 拒 EDITOR+null,
  // Task 5 接线后正常)。useState lazy init 稳定对象(不随 render 变,避免 hook deps editorCodeRef
  // 抖动致 onRun 重创);不用 useRef.current in render(react-hooks/refs 规则)。
  const [fallbackCodeRef] = useState<EditorCodeRef>(() => ({ current: null }))
  const codeRef = editorCodeRef ?? fallbackCodeRef

  const { messages, isRunning, model, setModel, codeSource, setCodeSource, onRun, onCancel } = useAssistantChat(
    strategy?.id ?? null,
    availableModels,
    codeRef,
  )

  // 自定义 Welcome(替代默认英文 ThreadWelcome):中文 h1 + SUGGESTIONS chips。
  // chips 点击直接 onRun(发送建议);assistant-ui SuggestionPrimitive 数据源配 runtime suggestions 复杂,
  // 直接 button onClick 简单 + 保留旧策略建议引导。闭包 onRun/llmKeyId,useCallback 稳定。
  const Welcome = useCallback(
    () => (
      <div className="mb-6 flex flex-col items-center px-4 text-center">
        <h1 className="text-h2 font-semibold text-text-primary">我可以帮你改进或调试策略</h1>
        <div className="mt-4 flex flex-wrap justify-center gap-2">
          {SUGGESTIONS.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => onRun(s, llmKeyId)}
              className="rounded-full border border-border-soft bg-surface-card-2 px-2.5 py-1 text-[11px] text-text-secondary transition hover:bg-surface-3"
            >
              {s}
            </button>
          ))}
        </div>
      </div>
    ),
    [onRun, llmKeyId],
  )

  const runtime = useExternalStoreRuntime({
    isRunning,
    messages,
    convertMessage,
    onNew: async (msg: AppendMessage) => {
      const text = msg.content[0]?.type === 'text' ? msg.content[0].text : ''
      onRun(text, llmKeyId)
    },
    onCancel: async () => onCancel(),
  })

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

      {/* Thread + Composer(assistant-ui,流式增量 + markdown + cancel) */}
      <div className="flex-1 overflow-hidden">
        <TooltipProvider>
          <AssistantRuntimeProvider runtime={runtime}>
            <Thread components={{ Welcome }} />
          </AssistantRuntimeProvider>
        </TooltipProvider>
      </div>

      {/* model + 代码版本切换(Composer 上方一行;版本=策略代码来源 editor/draft/published,spec §5 M5) */}
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
          <SelectTrigger className="h-7 w-48 text-[11px]">
            <SelectValue
              placeholder={availableModels.length === 0 ? '去设置页配模型' : '选择模型'}
            />
          </SelectTrigger>
          <SelectContent>
            {availableModels.map((m) => (
              <SelectItem key={m} value={m} className="text-[11px]">
                {m}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={codeSource}
          onValueChange={(v) => setCodeSource(v as CodeSource)}
        >
          <SelectTrigger className="h-7 w-28 text-[11px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="EDITOR" className="text-[11px]">编辑器</SelectItem>
            <SelectItem value="DRAFT" className="text-[11px]">草稿</SelectItem>
            <SelectItem value="PUBLISHED" className="text-[11px]">已发布</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>
  )
}
