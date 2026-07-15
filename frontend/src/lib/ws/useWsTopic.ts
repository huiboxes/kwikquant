import { useEffect, useRef } from 'react'
import { getWsConnection } from './ConnectionManager'

/**
 * useWsTopic — 订阅 STOMP topic,收到消息调 handler。destination 变或 unmount 退订。
 *
 * handler 用 ref 持有最新闭包,不进 effect 依赖 —— destination 不变时不重订阅,
 * 避免 handler 重渲染导致反复 subscribe/unsubscribe。
 *
 * 限制:ConnectionManager 用 destination 做 Map key,**同一 destination 全局只能一个订阅**。
 * 多组件订阅同 topic 会互相覆盖 —— 需订阅同 topic 的场景,应在 store 层订阅一次,组件读 store。
 *
 * destination 为 null 时不订阅(如 userId 未就绪),就绪后变为非空自动订阅。
 */
export function useWsTopic(
  destination: string | null,
  handler: (payload: unknown) => void,
): void {
  const handlerRef = useRef(handler)
  useEffect(() => {
    handlerRef.current = handler
  })

  useEffect(() => {
    if (!destination) return
    const unsub = getWsConnection().subscribe(destination, (payload) => {
      handlerRef.current(payload)
    })
    return unsub
  }, [destination])
}
