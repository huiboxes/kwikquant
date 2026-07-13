import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { streamChat } from '@/lib/sse'
import { AI_CHAT_URL, type ChatMessage } from '@/api/ai'
import { ApiError } from '@/lib/http'

/**
 * useStreamChat — AI 对话 SSE 流式 hook(StrategyPage AIChat 用)。
 *
 * 包装 src/lib/sse.ts streamChat(POST /api/v1/ai/chat,Flux<ServerSentEvent>,不套 envelope)。
 * 管理:messages 历史 + streaming flag + streamText(当前流式片段)+ draft 输入。
 *
 * send(text, llmKeyId, strategyId):
 *  - 追加 user msg 到 messages
 *  - 调 streamChat,body = AiChatRequest{ llmKeyId, messages: 历史+新, strategyId }
 *  - onChunk:累积 streamText(setStreaming true 期间)
 *  - onClose:把 streamText 作为最终 ai msg 推入 messages,清 streamText + streaming
 *  - onError:toast.error + setStreaming false(ApiError pre-stream 错误,如 401 无 LLM key)
 *
 * 中断:新 send 前 abort 上一条(AbortController ref);unmount 时 abort(effect cleanup 只 abort 不 setState)。
 *
 * React 19:setState 全在事件回调(send/onChunk/onClose)里,非 effect body,不触 set-state-in-effect 规则。
 */
export interface UseStreamChatReturn {
  messages: StreamMessage[]
  streaming: boolean
  streamText: string
  draft: string
  setDraft: (v: string) => void
  send: (text: string, llmKeyId: number | null, strategyId: number | null) => void
}

/** 扩展 ChatMessage 加 ts(原型 AIChat 显 m.ts 时间戳)。 */
export type StreamMessage = ChatMessage & { ts: string }

/** 当前时间 HH:mm(zh-CN 2-digit),原型 AIChat ts 风格。 */
function nowTs(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

export function useStreamChat(): UseStreamChatReturn {
  const initialMessages: StreamMessage[] = [
    {
      role: 'ai',
      content: '我已加载策略上下文(指标依赖、入场条件、风控参数)。需要我帮你改进或加新功能?',
      ts: nowTs(),
    },
  ]
  const [messages, setMessages] = useState<StreamMessage[]>(initialMessages)
  // ref 同步持有最新 messages,send 读 ref 拼请求 body(避开 setMessages updater 异步 stale closure)
  const messagesRef = useRef<StreamMessage[]>(initialMessages)
  const [streaming, setStreaming] = useState(false)
  const [streamText, setStreamText] = useState('')
  const [draft, setDraft] = useState('')

  const abortRef = useRef<AbortController | null>(null)
  const streamTextRef = useRef('')
  // finalized flag:onError/onClose 触发后置 true,.catch() 跳过,防 idle timeout 双错误 toast
  const finalizedRef = useRef(false)

  /** 追加消息:同步更新 ref + state(ref 立即可读,state 驱动渲染)。useCallback 稳定引用。 */
  const appendMessage = useCallback((msg: StreamMessage) => {
    const next = [...messagesRef.current, msg]
    messagesRef.current = next
    setMessages(next)
  }, [])

  // unmount 时中断流(只 abort,不 setState — 避免 unmount 后 setState 警告)
  useEffect(() => {
    return () => {
      abortRef.current?.abort()
    }
  }, [])

  const send = useCallback(
    (text: string, llmKeyId: number | null, strategyId: number | null) => {
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
      // body.messages = ChatMessage[](role+content,无 ts)
      const bodyMessages: ChatMessage[] = [...messagesRef.current].map((m) => ({
        role: m.role,
        content: m.content,
      }))

      setDraft('')
      setStreaming(true)
      setStreamText('')
      streamTextRef.current = ''
      finalizedRef.current = false

      const body = {
        llmKeyId,
        messages: bodyMessages,
        ...(strategyId != null ? { strategyId } : {}),
      }

      streamChat(
        AI_CHAT_URL,
        body,
        ctrl.signal,
        {
          onChunk: (data) => {
            // SSE data 是文本片段,累积
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
            }
            setStreaming(false)
            setStreamText('')
            streamTextRef.current = ''
          },
        },
        { idleTimeoutMs: 60_000 },
      ).catch((e: unknown) => {
        if (finalizedRef.current) return // onError/onClose 已处理,跳过(防 idle timeout 双 toast)
        if (ctrl.signal.aborted) return // Stop 静默
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
    [streaming, appendMessage],
  )

  return { messages, streaming, streamText, draft, setDraft, send }
}
