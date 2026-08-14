import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { streamChat } from '@/lib/sse'
import { AI_CHAT_URL, fetchChatHistory, saveAiMessage, type ChatMessage } from '@/api/ai'
import { ApiError } from '@/lib/http'

/**
 * useAssistantChat — AI 对话 SSE 流式 hook(自建 ChatThread 的 state 层)。
 *
 * 弃 assistant-ui ExternalStoreRuntime(legacy 路径 + 卡住根因)。messages state 直连
 * ChatThread 渲染,无 runtime 中间层 → appendMessage 后 setMessages 立即反映到 DOM(解症状 1:
 * "发消息后不立即显示,要等 AI 响应")。
 *
 * rAF 批处理(解症状 2 "消息多了卡住"):onChunk 不直接 setMessages,累积到 bufferRef,
 * requestAnimationFrame 每帧最多一次 flush → setMessages。每 chunk 一次 setMessages 改为每帧一次,
 * 频率从 ~几十/秒 降到 60/秒,且 flush 只更新 last assistant(React.memo 隔离历史消息)。
 *
 * 七项职责:
 *  1. messages + isRunning;streaming 文本进 last assistant partial content(rAF flush 后)
 *  2. history 加载 + role 重映射 ai→assistant
 *  3. abort 上一条 + unmount abort
 *  4. finalizedRef 防 onError/onClose/onCancel 三路去重
 *  5. model localStorage per-strategy + 陈旧归零
 *  6. saveAiMessage(onClose 存 AI 回复)
 *  7. idle timeout 60s
 *
 * editor 模式 sourceCode 从 editorCodeRef 读(ref 非 props,1MB 高频 onChange 不 setState)。
 */

export interface StoreMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  ts: string
  /** assistant 消息错误态(SSE onError/catch 时设,last assistant 标记 error + 内联重试按钮)。
   *  有 error 则 MessageItem 渲染错误提示替代 content;retryLast 删 last error assistant 重发。 */
  error?: string
}

function newId(): string {
  return globalThis.crypto.randomUUID()
}

export type CodeSource = 'EDITOR' | 'DRAFT' | 'PUBLISHED'

export interface EditorCodeRef {
  current: string | null
}

export interface UseAssistantChatReturn {
  messages: StoreMessage[]
  isRunning: boolean
  model: string
  setModel: (v: string) => void
  codeSource: CodeSource
  setCodeSource: (v: CodeSource) => void
  /** 发送用户消息。llmKeyId null 时 toast 警告不调 streamChat。 */
  onRun: (text: string, llmKeyId: number | null) => void
  /** 停止:abort fetch + flush 残留 buffer + 删空 placeholder + 归零 isRunning。 */
  onCancel: () => void
  /** 重试上一次失败的 AI 回复(删 last error assistant,用 last user 重新请求)。 */
  retryLast: () => void
}

function nowTs(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const STORAGE_PREFIX = 'ai-chat-model-'
const IDLE_TIMEOUT_MS = 60_000

export function useAssistantChat(
  strategyId: number | null,
  availableModels: string[],
  editorCodeRef: EditorCodeRef,
): UseAssistantChatReturn {
  const [messages, setMessages] = useState<StoreMessage[]>([])
  const messagesRef = useRef<StoreMessage[]>([])
  const [isRunning, setIsRunning] = useState(false)
  const [model, setModel] = useState<string>('')
  const [codeSource, setCodeSource] = useState<CodeSource>('EDITOR')

  const abortRef = useRef<AbortController | null>(null)
  const finalizedRef = useRef(false)
  // rAF 批处理:onChunk 累积到 buffer,每帧最多一次 setMessages(解症状 2)
  const bufferRef = useRef('')
  const rafRef = useRef<number | null>(null)
  const lastLlmKeyIdRef = useRef<number | null>(null)

  const appendMessage = useCallback((msg: Omit<StoreMessage, 'id'>) => {
    const full: StoreMessage = { ...msg, id: newId() }
    const next = [...messagesRef.current, full]
    messagesRef.current = next
    setMessages(next)
  }, [])

  /** 删除最后一条消息(若为空 assistant placeholder 无 error,streaming 中止/空回复清理)。 */
  const popEmptyPlaceholder = useCallback(() => {
    const next = [...messagesRef.current]
    const last = next[next.length - 1]
    if (last && last.role === 'assistant' && last.content === '' && !last.error) {
      next.pop()
      messagesRef.current = next
      setMessages(next)
    }
  }, [])

  const cancelRaf = useCallback(() => {
    if (rafRef.current != null) {
      cancelAnimationFrame(rafRef.current)
      rafRef.current = null
    }
  }, [])

  /** 立即 flush:把 buffer 合并到 last assistant content + setMessages(取消待执行 rAF)。 */
  const flushNow = useCallback(() => {
    cancelRaf()
    const buf = bufferRef.current
    if (!buf) return
    bufferRef.current = ''
    const next = [...messagesRef.current]
    const last = next[next.length - 1]
    if (last && last.role === 'assistant') {
      next[next.length - 1] = { ...last, content: last.content + buf }
      messagesRef.current = next
      setMessages(next)
    }
  }, [cancelRaf])

  /** 调度 rAF flush(每帧最多一次 setMessages);已调度则跳过(批处理关键)。 */
  const scheduleFlush = useCallback(() => {
    if (rafRef.current != null) return
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null
      flushNow()
    })
  }, [flushNow])

  /** 标记 last assistant 错误态(flush 残留 buffer 后,保留 partial content + 加 error)。 */
  const setLastError = useCallback((msg: string) => {
    const next = [...messagesRef.current]
    const last = next[next.length - 1]
    if (last && last.role === 'assistant') {
      next[next.length - 1] = { ...last, error: msg }
      messagesRef.current = next
      setMessages(next)
    }
  }, [])

  // 进入策略加载历史 + model localStorage(strategyId 变化时)
  const modelsKey = availableModels.join(',')
  useEffect(() => {
    if (strategyId == null) return
    let cancelled = false
    const storageKey = `${STORAGE_PREFIX}${strategyId}`
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
          id: m.id != null ? String(m.id) : newId(),
          role: (m.role === 'user' ? 'user' : 'assistant') as StoreMessage['role'],
          content: m.content,
          ts: m.createdAt
            ? new Date(m.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
            : nowTs(),
        }))
        messagesRef.current = msgs
        setMessages(msgs)
        setModel(resolveModel())
      })
      .catch(() => {
        if (cancelled) return
        messagesRef.current = []
        setMessages([])
        setModel(resolveModel())
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- modelsKey 派生自 availableModels(join),作 dep 避免引用变化致频繁重 fetch
  }, [strategyId, modelsKey])

  // unmount 时中断流 + 取消 rAF(只 abort,不 setState)
  useEffect(() => {
    return () => {
      abortRef.current?.abort()
      cancelRaf()
    }
  }, [cancelRaf])

  /**
   * 启动流:append placeholder assistant + setIsRunning + streamChat。
   * bodyMessages 不含 placeholder(snapshot 在 append 之前取)。
   * onChunk → buffer + rAF schedule;onError/onClose/onCancel → cancelRaf + flushNow + 定稿。
   */
  const startStream = useCallback(
    (bodyMessages: ChatMessage[], llmKeyId: number | null) => {
      abortRef.current?.abort()
      const ctrl = new AbortController()
      abortRef.current = ctrl
      finalizedRef.current = false
      lastLlmKeyIdRef.current = llmKeyId

      appendMessage({ role: 'assistant', content: '', ts: nowTs() })
      setIsRunning(true)

      const bodyModel = model.trim() || undefined
      if (strategyId != null) {
        localStorage.setItem(`${STORAGE_PREFIX}${strategyId}`, model)
      }

      const body = {
        llmKeyId,
        messages: bodyMessages,
        ...(strategyId != null ? { strategyId } : {}),
        ...(bodyModel ? { model: bodyModel } : {}),
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
            bufferRef.current += data
            scheduleFlush()
          },
          onError: (data) => {
            if (finalizedRef.current) return
            finalizedRef.current = true
            cancelRaf()
            flushNow()
            setLastError(data || 'AI 流式错误')
            setIsRunning(false)
            toast.error('AI 流式错误', { description: data || '请重试' })
          },
          onClose: () => {
            if (finalizedRef.current) return
            finalizedRef.current = true
            cancelRaf()
            flushNow()
            const last = messagesRef.current[messagesRef.current.length - 1]
            const finalText = last && last.role === 'assistant' ? last.content : ''
            if (!finalText) {
              // 空回复删 placeholder(不留空气泡)
              popEmptyPlaceholder()
            } else if (strategyId != null) {
              saveAiMessage(strategyId, { content: finalText, model: bodyModel ?? '' }).catch((e: unknown) => {
                // 持久化失败不影响已展示的回复,但需提示用户(历史未落库,刷新会丢)
                const msg = e instanceof Error ? e.message : '历史保存失败'
                toast.error('AI 回复未能保存到历史', { description: msg })
              })
            }
            setIsRunning(false)
          },
        },
        { idleTimeoutMs: IDLE_TIMEOUT_MS },
      ).catch((e: unknown) => {
        if (finalizedRef.current) return
        if (ctrl.signal.aborted) return
        finalizedRef.current = true
        cancelRaf()
        flushNow()
        if (e instanceof ApiError) {
          setLastError(e.message || 'AI 对话失败')
          if (e.isUnauthorized) toast.error('未认证,请重新登录')
          else toast.error(e.message || 'AI 对话失败')
        } else {
          setLastError('AI 对话失败,请重试')
          toast.error('AI 对话失败,请重试')
        }
        setIsRunning(false)
      })
    },
    [appendMessage, scheduleFlush, cancelRaf, flushNow, setLastError, popEmptyPlaceholder, strategyId, model, codeSource, editorCodeRef],
  )

  const onRun = useCallback(
    (text: string, llmKeyId: number | null) => {
      const trimmed = text.trim()
      if (!trimmed || isRunning) return
      if (llmKeyId == null) {
        toast.warning('请先在设置页配置 LLM Key')
        return
      }
      // 乐观渲染:立即 append user → setMessages 直连 ChatThread DOM,无 ExternalStore 中间层(解症状 1)
      appendMessage({ role: 'user', content: trimmed, ts: nowTs() })
      // body snapshot:含刚 append 的 user,不含 placeholder(startStream 还没 append)
      // 截断到最近 60 条(后端 @Size 200 + 服务端截断 100 兜底;前端先截省带宽且防长会话溢出)
      const bodyMessages: ChatMessage[] = [...messagesRef.current].slice(-60).map((m) => ({
        role: m.role,
        content: m.content,
      }))
      startStream(bodyMessages, llmKeyId)
    },
    [isRunning, appendMessage, startStream],
  )

  const retryLast = useCallback(() => {
    if (isRunning) return
    const msgs = messagesRef.current
    const last = msgs[msgs.length - 1]
    if (!last || last.role !== 'assistant' || !last.error) return
    // 删 last error assistant,保留 last user(复用 context 重发)
    const without = msgs.slice(0, -1)
    messagesRef.current = without
    setMessages(without)
    const bodyMessages: ChatMessage[] = without.map((m) => ({ role: m.role, content: m.content }))
    startStream(bodyMessages, lastLlmKeyIdRef.current)
  }, [isRunning, startStream])

  const onCancel = useCallback(() => {
    abortRef.current?.abort()
    if (finalizedRef.current) return
    finalizedRef.current = true
    // abort 后 streamChat catch signal.aborted 静默 return,onClose/onError 不触发,
    // isRunning 需手动归零;空 placeholder 删(未收到 chunk)
    cancelRaf()
    flushNow()
    popEmptyPlaceholder()
    setIsRunning(false)
  }, [cancelRaf, flushNow, popEmptyPlaceholder])

  return { messages, isRunning, model, setModel, codeSource, setCodeSource, onRun, onCancel, retryLast }
}
