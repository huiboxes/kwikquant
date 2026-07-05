import { useEffect } from 'react'
import { getWsConnection } from '@/lib/ws/ConnectionManager'

/**
 * useWsSubscription — 订阅 WS 主题(spec §5 step 9)。
 *
 * 单例 ConnectionManager 幂等 connect(多次调安全),subscribe 返 unsubscribe,
 * unmount 时自动 unsubscribe。重连后 ConnectionManager 自动重订阅(无需 hook 处理)。
 *
 * @param destination STOMP 主题,如 /topic/notifications/{userId}
 * @param handler 消息回调(payload 已 JSON.parse)
 */
export function useWsSubscription(
  destination: string | null,
  handler: (payload: unknown) => void,
): void {
  useEffect(() => {
    if (!destination) return
    const url = import.meta.env.VITE_WS_URL ?? '/ws-endpoint'
    const conn = getWsConnection(url)
    conn.connect()
    const unsub = conn.subscribe(destination, handler)
    return unsub
    // handler 是引用,调用方应 useCallback 稳定引用避免重订阅
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [destination])
}
