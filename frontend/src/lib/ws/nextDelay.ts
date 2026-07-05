/**
 * WS 重连退避序列(spec §5 step 9)。
 *
 * 1s → 2s → 5s → 10s → 30s(上限)。@stomp/stompjs 的 reconnectDelay 是固定延时非指数退避,
 * 故设 reconnectDelay:0 禁用库内自动重连,由 ConnectionManager.beforeConnect 手动 setTimeout。
 *
 * 纯函数,可单测(不启 WS)。
 */
const BACKOFF_SEQUENCE = [1_000, 2_000, 5_000, 10_000, 30_000] as const

/** 上限退避(ms),attempt 超出序列长度后固定此值 */
export const MAX_BACKOFF_MS = 30_000

/**
 * @param attempt 重连次数(0 = 首次重连,1 = 第二次,...)
 * @returns 下次重连延迟(ms)
 */
export function nextDelay(attempt: number): number {
  if (attempt < 0) return BACKOFF_SEQUENCE[0]
  if (attempt >= BACKOFF_SEQUENCE.length) return MAX_BACKOFF_MS
  return BACKOFF_SEQUENCE[attempt]
}
