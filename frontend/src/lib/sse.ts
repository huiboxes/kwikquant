import { ApiError } from './http'
import { useAuthStore } from '@/stores/authStore'

/**
 * SSE stream util(spec §5 step 8)。
 *
 * AiChatService 后端发 SSE 帧:
 *   event: message  → data 是 chunk(打字机增量)
 *   event: error    → data 是错误描述
 *   event: done     → 终止帧(契约 E 已落地,stream 结束信号)
 * stream 自然结束(无 done 帧)也触发 onClose。
 *
 * idle 超时兜底:30s 无 chunk 判异常(AiChatService 卡死/网络断),触发 onError + 关流。
 * Stop = AbortController.abort(调用方传 signal)。
 *
 * pre-stream HTTP 错误(非 200):parse body 取 ApiResponse code,抛 ApiError。
 *   401(1001 token) / 404(4001 key 不存在) / 403(4003 key 非本人) /
 *   500(8002 LLM_KEY_INVALID_PROVIDER) / 502(8003 LLM_PROVIDER_ERROR)
 *
 * 401 不重放:AI chat POST 非幂等(tokens 双扣),AISidebar 标记 skipAuthRetry,此处直接抛。
 */

export interface ParsedSseFrame {
  event: string
  data: string
}

export interface SseHandlers {
  onChunk: (data: string) => void
  onError: (data: string) => void
  onClose: () => void
}

/** SSE 默认 event 名(message) */
const DEFAULT_EVENT = 'message'

/**
 * 从单个 SSE 帧(已按 \n\n 切出的 raw 块)解析 event + data。
 * 纯函数,可单测。data 多行用 \n 拼接(SSE 规范)。
 */
export function parseSseFrame(raw: string): ParsedSseFrame | null {
  let event = DEFAULT_EVENT
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // SSE 规范:data: 后有一个前导空格,去掉
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    // 忽略 id:/retry:/注释行(:...)
  }
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

/**
 * 从 SSE buffer 解析所有完整帧(以 \n\n 分隔),返回已解析帧 + 剩余未完成 buffer。
 * 纯函数,可单测。调用方累积 buffer,每次新增数据后调此函数取完整帧。
 */
export function parseSseFrames(buffer: string): { frames: ParsedSseFrame[]; rest: string } {
  const frames: ParsedSseFrame[] = []
  let rest = buffer
  let idx = rest.indexOf('\n\n')
  while (idx !== -1) {
    const raw = rest.slice(0, idx)
    rest = rest.slice(idx + 2)
    const frame = parseSseFrame(raw)
    if (frame) frames.push(frame)
    idx = rest.indexOf('\n\n')
  }
  return { frames, rest }
}

export interface StreamChatOptions {
  idleTimeoutMs?: number
}

/**
 * 发起 SSE POST 流式请求,解析帧分发给 handlers。
 * @throws ApiError — pre-stream HTTP 错误(401/404/403/500/502)
 * Stop:调用方 AbortController.abort(),streamChat 静默返回(不抛)。
 */
export async function streamChat(
  url: string,
  body: unknown,
  signal: AbortSignal,
  handlers: SseHandlers,
  options: StreamChatOptions = {},
): Promise<void> {
  const token = useAuthStore.getState().accessToken
  const headers = new Headers({
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  })
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let res: Response
  try {
    res = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal,
      credentials: 'include',
    })
  } catch (e) {
    // Stop(abort)静默;其他网络异常抛
    if (signal.aborted) return
    throw e instanceof Error ? new ApiError(5001, e.message) : new ApiError(5001, '网络异常')
  }

  if (!res.ok) {
    const errBody = (await res.json().catch(() => ({}))) as { code?: number; message?: string }
    const code = errBody.code ?? 0
    const message = errBody.message ?? `HTTP ${res.status}`
    throw new ApiError(code, message, res.status)
  }

  if (!res.body) {
    handlers.onClose()
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const idleTimeoutMs = options.idleTimeoutMs ?? 30_000
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  let closed = false

  const clearIdle = () => {
    if (idleTimer) {
      clearTimeout(idleTimer)
      idleTimer = undefined
    }
  }
  const resetIdle = () => {
    clearIdle()
    idleTimer = setTimeout(() => {
      idleTimer = undefined
      reader.cancel().catch(() => {})
      if (!closed) {
        closed = true
        handlers.onError('连接空闲超时(30s 无数据)')
      }
    }, idleTimeoutMs)
  }
  resetIdle()

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { frames, rest } = parseSseFrames(buffer)
      buffer = rest
      for (const frame of frames) {
        resetIdle()
        if (frame.event === 'message') {
          handlers.onChunk(frame.data)
        } else if (frame.event === 'error') {
          if (!closed) {
            closed = true
            handlers.onError(frame.data)
          }
        } else if (frame.event === 'done') {
          reader.cancel().catch(() => {})
          if (!closed) {
            closed = true
            handlers.onClose()
          }
          return
        }
      }
    }
    clearIdle()
    if (!closed) {
      closed = true
      handlers.onClose()
    }
  } catch (e) {
    clearIdle()
    if (signal.aborted) return // Stop 按钮,静默
    throw e instanceof Error ? new ApiError(5001, e.message) : new ApiError(5001, 'stream 异常')
  }
}
