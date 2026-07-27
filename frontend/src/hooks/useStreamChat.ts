import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { streamChat } from '@/lib/sse'
import { AI_CHAT_URL, fetchChatHistory, saveAiMessage, type ChatMessage } from '@/api/ai'
import { ApiError } from '@/lib/http'

/**
 * useStreamChat — AI 对话 SSE 流式 hook(StrategyPage SessionPanel 用)。
 *
 * 包装 src/lib/sse.ts streamChat(POST /api/v1/ai/chat,Flux<ServerSentEvent>,不套 envelope)。
 * 管理:messages 历史 + streaming flag + streamText(当前流式片段)+ draft 输入 + model(会话级覆盖,localStorage 持久化)。
 *
 * 进入策略(strategyId 变化):GET /api/v1/strategies/{id}/ai/messages 加载历史替换欢迎语(空则显欢迎语)。
 *
 * send(text, llmKeyId):
 *  - 追加 user msg 到 messages
 *  - 调 streamChat,body = AiChatRequest{ llmKeyId, messages: 历史+新, strategyId, model? }
 *  - model 从 state 读(localStorage 持久化,留空用 key 默认)
 *  - onChunk:累积 streamText
 *  - onClose:把 streamText 作为最终 ai msg 推入 messages + POST /strategies/{id}/ai/messages 保存 AI 回复(role=ai,content=完整文本,model=本次用的)
 *  - onError:toast.error + setStreaming false
 *
 * model localStorage key: ai-chat-model-{strategyId}(per-strategy,刷新保留)。
 *
 * 中断:新 send 前 abort 上一条;unmount 时 abort。
 */

export interface UseStreamChatReturn {
  messages: StreamMessage[]
  streaming: boolean
  streamText: string
  draft: string
  setDraft: (v: string) => void
  model: string
  setModel: (v: string) => void
  send: (text: string, llmKeyId: number | null) => void
}

/** 扩展 ChatMessage 加 ts(原型 AIChat 显 m.ts 时间戳)。 */
export type StreamMessage = ChatMessage & { ts: string }

/** 当前时间 HH:mm(zh-CN 2-digit),原型 AIChat ts 风格。 */
function nowTs(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

/** 进入无策略或加载失败时的欢迎语。 */
const WELCOME: StreamMessage[] = [
  {
    role: 'ai',
    content: '我已加载策略上下文(指标依赖、入场条件、风控参数)。需要我帮你改进或加新功能?',
    ts: nowTs(),
  },
]

/** localStorage key 前缀(per-strategy model 持久化)。 */
const STORAGE_PREFIX = 'ai-chat-model-'

export function useStreamChat(strategyId: number | null): UseStreamChatReturn {
  const [messages, setMessages] = useState<StreamMessage[]>(WELCOME)
  // ref 同步持有最新 messages,send 读 ref 拼请求 body(避开 setMessages updater 异步 stale closure)
  const messagesRef = useRef<StreamMessage[]>(WELCOME)
  const [streaming, setStreaming] = useState(false)
  const [streamText, setStreamText] = useState('')
  const [draft, setDraft] = useState('')
  const [model, setModel] = useState<string>('')

  const abortRef = useRef<AbortController | null>(null)
  const streamTextRef = useRef('')
  // finalized flag:onError/onClose 触发后置 true,.catch() 跳过,防 idle timeout 双错误 toast
  const finalizedRef = useRef(false)

  /** 追加消息:同步更新 ref + state。 */
  const appendMessage = useCallback((msg: StreamMessage) => {
    const next = [...messagesRef.current, msg]
    messagesRef.current = next
    setMessages(next)
  }, [])

  // 进入策略加载历史 + model localStorage(strategyId 变化时)。
  // setState 全放 fetch then/catch async,避免 effect body 同步 setState 触发 cascading renders
  // (react-compiler 规则);strategyId==null 时 return 保持 initial/上次(切回非 null 时下方 fetch 重载)。
  useEffect(() => {
    if (strategyId == null) return
    let cancelled = false
    const storageKey = `${STORAGE_PREFIX}${strategyId}`
    fetchChatHistory(strategyId)
      .then((history) => {
        if (cancelled) return
        const msgs: StreamMessage[] = history.map((m) => ({
          role: (m.role === 'ai' ? 'ai' : 'user') as ChatMessage['role'],
          content: m.content,
          ts: m.createdAt
            ? new Date(m.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
            : nowTs(),
        }))
        const next = msgs.length > 0 ? msgs : WELCOME
        messagesRef.current = next
        setMessages(next)
        setModel(localStorage.getItem(storageKey) ?? '')
      })
      .catch(() => {
        if (cancelled) return
        messagesRef.current = WELCOME
        setMessages(WELCOME)
        setModel(localStorage.getItem(storageKey) ?? '')
      })
    return () => {
      cancelled = true
    }
  }, [strategyId])

  // unmount 时中断流(只 abort,不 setState)
  useEffect(() => {
    return () => {
      abortRef.current?.abort()
    }
  }, [])

  const send = useCallback(
    (text: string, llmKeyId: number | null) => {
      const trimmed = text.trim()
      if (!trimmed || streaming) return
      if (llmKeyId == null) {
        toast.warning('请先在设置页配置 LLM Key')
        return
      }

      // 中断上一条流
      abortRef.current?.abort()
      const ctrl = new AbortController()
      abortRef.current = ctrl

      const userMsg: StreamMessage = { role: 'user', content: trimmed, ts: nowTs() }
      appendMessage(userMsg)
      const bodyMessages: ChatMessage[] = [...messagesRef.current].map((m) => ({
        role: m.role,
        content: m.content,
      }))

      setDraft('')
      setStreaming(true)
      setStreamText('')
      streamTextRef.current = ''
      finalizedRef.current = false

      // model 优先级(tech-design §3.2):会话级 model(state,localStorage 持久化) > key 默认(后端 fallback)
      // 留空不传 model,后端 AiChatService 用 key.getModel() → adapter.defaultModel()
      const bodyModel = model.trim() || undefined
      if (strategyId != null) {
        localStorage.setItem(`${STORAGE_PREFIX}${strategyId}`, model)
      }

      const body = {
        llmKeyId,
        messages: bodyMessages,
        ...(strategyId != null ? { strategyId } : {}),
        ...(bodyModel ? { model: bodyModel } : {}),
      }

      streamChat(
        AI_CHAT_URL,
        body,
        ctrl.signal,
        {
          onChunk: (data) => {
            streamTextRef.current += data
            setStreamText(streamTextRef.current)
          },
          onError: (data) => {
            if (finalizedRef.current) return
            finalizedRef.current = true
            setStreaming(false)
            setStreamText('')
            streamTextRef.current = ''
            toast.error('AI 流式错误', { description: data || '请重试' })
          },
          onClose: () => {
            if (finalizedRef.current) return
            finalizedRef.current = true
            const finalText = streamTextRef.current
            if (finalText) {
              appendMessage({ role: 'ai', content: finalText, ts: nowTs() })
              // 保存 AI 回复到 DB(POST /strategies/{id}/ai/messages,role=ai;user 消息后端 /ai/chat 已存)
              if (strategyId != null) {
                saveAiMessage(strategyId, { content: finalText, model: bodyModel ?? '' }).catch(
                  () => {},
                )
              }
            }
            setStreaming(false)
            setStreamText('')
            streamTextRef.current = ''
          },
        },
        { idleTimeoutMs: 60_000 },
      ).catch((e: unknown) => {
        if (finalizedRef.current) return
        if (ctrl.signal.aborted) return
        finalizedRef.current = true
        setStreaming(false)
        setStreamText('')
        streamTextRef.current = ''
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
    [streaming, appendMessage, strategyId, model],
  )

  return { messages, streaming, streamText, draft, setDraft, model, setModel, send }
}
