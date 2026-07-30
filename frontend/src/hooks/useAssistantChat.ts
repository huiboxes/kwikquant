import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { streamChat } from '@/lib/sse'
import { AI_CHAT_URL, fetchChatHistory, saveAiMessage, type ChatMessage } from '@/api/ai'
import { ApiError } from '@/lib/http'

/**
 * useAssistantChat — AI 对话 SSE 流式 hook,assistant-ui ExternalStoreRuntime adapter 层(spec §3/§4.2)。
 *
 * 适配 ExternalStoreRuntime 形状:messages + isRunning + onRun(text) + onCancel。
 * SessionPanel 调 useExternalStoreRuntime 组装 runtime,Thread 增量渲染。
 *
 * 复刻 spec §4.2 七项职责(从旧 useStreamChat 迁移):
 *  1. messages state + isRunning;**streaming 文本进 messages 最后一条 assistant partial content**
 *     (assistant-ui Thread 增量渲染,非独立 streamText)
 *  2. history 加载(strategyId 变化重 fetch)+ role 重映射 ai→assistant
 *  3. abort 上一条 + unmount abort
 *  4. finalizedRef 防 idle timeout + onError + onClose 三路触发去重
 *  5. model localStorage per-strategy 持久化 + 陈旧归零
 *  6. saveAiMessage(onClose 存 AI 回复;user 消息后端 /ai/chat 入口 blocking 存)
 *  7. idle timeout 60s
 *
 * editor 模式 sourceCode 从 editorCodeRef 读(ref 非 props,1MB 高频 onChange 不 setState 致 re-render 雪崩,spec §4.2 m3)。
 * codeSource state(Task 5 版本切换器,默认 EDITOR;draft/published 后端注入 sourceCode,不传)。
 */

/** store 消息(role 对齐 LLM 协议 system/user/assistant,前端 AI 消息用 assistant 不用 ai)。 */
export interface StoreMessage {
  role: 'user' | 'assistant'
  content: string
  ts: string
}

/** 策略代码来源(对齐 api-gen CodeSource 枚举,大写;editor 前端传 sourceCode,draft/published 后端注入)。 */
export type CodeSource = 'EDITOR' | 'DRAFT' | 'PUBLISHED'

/** 编辑器实时 code ref(父组件编辑器 onChange 写 ref.current,不 setState)。 */
export interface EditorCodeRef {
  current: string | null
}

export interface UseAssistantChatReturn {
  messages: StoreMessage[]
  isRunning: boolean
  model: string
  setModel: (v: string) => void
  /** 策略代码来源(版本切换器,默认 EDITOR)。 */
  codeSource: CodeSource
  setCodeSource: (v: CodeSource) => void
  /** 发送用户消息。llmKeyId null 时 toast 警告不调 streamChat(未配 key)。 */
  onRun: (text: string, llmKeyId: number | null) => void
  /** 停止:abort fetch + 删空 placeholder + 归零 isRunning。 */
  onCancel: () => void
}

/** 当前时间 HH:mm(zh-CN 2-digit),原型 AIChat ts 风格。 */
function nowTs(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

/** 进入无策略或加载失败时的欢迎语。 */
const WELCOME: StoreMessage[] = [
  {
    role: 'assistant',
    content: '我已加载策略上下文(指标依赖、入场条件、风控参数)。需要我帮你改进或加新功能?',
    ts: nowTs(),
  },
]

/** localStorage key 前缀(per-strategy model 持久化)。 */
const STORAGE_PREFIX = 'ai-chat-model-'

/** idle timeout 60s(spec §4.2 第 7 项,旧 useStreamChat.ts:209)。 */
const IDLE_TIMEOUT_MS = 60_000

export function useAssistantChat(
  strategyId: number | null,
  availableModels: string[],
  editorCodeRef: EditorCodeRef,
): UseAssistantChatReturn {
  const [messages, setMessages] = useState<StoreMessage[]>(WELCOME)
  // ref 同步持有最新 messages,onRun 读 ref 拼请求 body(避开 setMessages updater 异步 stale closure)
  const messagesRef = useRef<StoreMessage[]>(WELCOME)
  const [isRunning, setIsRunning] = useState(false)
  const [model, setModel] = useState<string>('')
  const [codeSource, setCodeSource] = useState<CodeSource>('EDITOR')

  const abortRef = useRef<AbortController | null>(null)
  // finalized flag:onError/onClose/onCancel 触发后置 true,.catch() 跳过,防 idle timeout 双错误 toast
  const finalizedRef = useRef(false)

  /** 追加消息:同步更新 ref + state。 */
  const appendMessage = useCallback((msg: StoreMessage) => {
    const next = [...messagesRef.current, msg]
    messagesRef.current = next
    setMessages(next)
  }, [])

  /** 删除最后一条消息(若为空 assistant placeholder,streaming 中止/空回复清理)。 */
  const popEmptyPlaceholder = useCallback(() => {
    const next = [...messagesRef.current]
    const last = next[next.length - 1]
    if (last && last.role === 'assistant' && last.content === '') {
      next.pop()
      messagesRef.current = next
      setMessages(next)
    }
  }, [])

  /** 累积 chunk 到最后一条 assistant content(partial update,Thread 增量渲染,spec §4.2 第 1 项)。 */
  const appendChunkToLastAssistant = useCallback((data: string) => {
    const next = [...messagesRef.current]
    const last = next[next.length - 1]
    if (last && last.role === 'assistant') {
      next[next.length - 1] = { ...last, content: last.content + data }
      messagesRef.current = next
      setMessages(next)
    }
  }, [])

  // 进入策略加载历史 + model localStorage(strategyId 变化时)。
  // availableModels 变化也重跑(陈旧归零需 key 加载后的列表)。
  // setState 全放 fetch then/catch async,避免 effect body 同步 setState 触发 cascading renders;
  // strategyId==null 时 return 保持 initial(切回非 null 时下方 fetch 重载)。
  const modelsKey = availableModels.join(',')
  useEffect(() => {
    if (strategyId == null) return
    let cancelled = false
    const storageKey = `${STORAGE_PREFIX}${strategyId}`
    // 陈旧值归零:stored 不在当前 availableModels 列表(且列表非空)→ 取首项;否则留 stored
    const resolveModel = () => {
      const stored = localStorage.getItem(storageKey) ?? ''
      const valid = stored.length > 0 && availableModels.length > 0 && availableModels.includes(stored)
      const initial = valid ? stored : (availableModels[0] ?? '')
      if (availableModels.length > 0 && !valid) {
        localStorage.setItem(storageKey, initial)
      }
      return initial
    }
    fetchChatHistory(strategyId)
      .then((history) => {
        if (cancelled) return
        const msgs: StoreMessage[] = history.map((m) => ({
          role: (m.role === 'user' ? 'user' : 'assistant') as StoreMessage['role'],
          content: m.content,
          ts: m.createdAt
            ? new Date(m.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
            : nowTs(),
        }))
        const next = msgs.length > 0 ? msgs : WELCOME
        messagesRef.current = next
        setMessages(next)
        setModel(resolveModel())
      })
      .catch(() => {
        if (cancelled) return
        messagesRef.current = WELCOME
        setMessages(WELCOME)
        setModel(resolveModel())
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- modelsKey 派生自 availableModels(join),作 dep 避免引用变化致频繁重 fetch history
  }, [strategyId, modelsKey])

  // unmount 时中断流(只 abort,不 setState)
  useEffect(() => {
    return () => {
      abortRef.current?.abort()
    }
  }, [])

  const onRun = useCallback(
    (text: string, llmKeyId: number | null) => {
      const trimmed = text.trim()
      if (!trimmed || isRunning) return
      if (llmKeyId == null) {
        toast.warning('请先在设置页配置 LLM Key')
        return
      }

      // 中断上一条流
      abortRef.current?.abort()
      const ctrl = new AbortController()
      abortRef.current = ctrl
      finalizedRef.current = false

      const userMsg: StoreMessage = { role: 'user', content: trimmed, ts: nowTs() }
      appendMessage(userMsg)

      // body snapshot(含 WELCOME/历史 + userMsg,不含 placeholder assistant — 避免发空 message 给后端)
      const bodyMessages: ChatMessage[] = [...messagesRef.current].map((m) => ({
        role: m.role,
        content: m.content,
      }))

      // placeholder assistant(content='')— streaming 进 messages,Thread 增量渲染(spec §4.2 第 1 项)
      appendMessage({ role: 'assistant', content: '', ts: nowTs() })

      setIsRunning(true)

      // model 优先级:会话级 model(state,localStorage 持久化) > key 默认(后端 fallback)
      const bodyModel = model.trim() || undefined
      if (strategyId != null) {
        localStorage.setItem(`${STORAGE_PREFIX}${strategyId}`, model)
      }

      const body = {
        llmKeyId,
        messages: bodyMessages,
        ...(strategyId != null ? { strategyId } : {}),
        ...(bodyModel ? { model: bodyModel } : {}),
        // codeSource 分支(spec §5 M5):editor 前端传 sourceCode(编辑器实时 code);
        // draft/published 后端注入(不传 sourceCode,省 1MB body + 后端可信 audit)
        ...(codeSource === 'EDITOR' && editorCodeRef.current != null
          ? { sourceCode: editorCodeRef.current }
          : {}),
        codeSource,
      }

      streamChat(
        AI_CHAT_URL,
        body,
        ctrl.signal,
        {
          onChunk: (data) => {
            appendChunkToLastAssistant(data)
          },
          onError: (data) => {
            if (finalizedRef.current) return
            finalizedRef.current = true
            popEmptyPlaceholder()
            setIsRunning(false)
            toast.error('AI 流式错误', { description: data || '请重试' })
          },
          onClose: () => {
            if (finalizedRef.current) return
            finalizedRef.current = true
            const last = messagesRef.current[messagesRef.current.length - 1]
            const finalText = last && last.role === 'assistant' ? last.content : ''
            if (!finalText) {
              // 空回复删 placeholder(不留空气泡)
              popEmptyPlaceholder()
            } else if (strategyId != null) {
              // 保存 AI 回复到 DB(POST /strategies/{id}/ai/messages,后端硬编码存 role=ai;
              // 前端 state 用 assistant 对齐 LLM 协议。user 消息后端 /ai/chat 已存)
              saveAiMessage(strategyId, { content: finalText, model: bodyModel ?? '' }).catch(() => {})
            }
            setIsRunning(false)
          },
        },
        { idleTimeoutMs: IDLE_TIMEOUT_MS },
      ).catch((e: unknown) => {
        if (finalizedRef.current) return
        if (ctrl.signal.aborted) return
        finalizedRef.current = true
        popEmptyPlaceholder()
        setIsRunning(false)
        if (e instanceof ApiError) {
          if (e.isUnauthorized) {
            toast.error('未认证,请重新登录')
          } else {
            toast.error(e.message || 'AI 对话失败')
          }
        } else {
          toast.error('AI 对话失败,请重试')
        }
      })
    },
    [isRunning, appendMessage, appendChunkToLastAssistant, popEmptyPlaceholder, strategyId, model, editorCodeRef, codeSource],
  )

  const onCancel = useCallback(() => {
    abortRef.current?.abort()
    if (finalizedRef.current) return
    finalizedRef.current = true
    // abort 后 streamChat catch signal.aborted 静默 return(sse.ts:184),onClose/onError 不触发,
    // isRunning 需手动归零;空 placeholder 删(未收到 chunk)
    popEmptyPlaceholder()
    setIsRunning(false)
  }, [popEmptyPlaceholder])

  return { messages, isRunning, model, setModel, codeSource, setCodeSource, onRun, onCancel }
}
