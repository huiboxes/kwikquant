import { useCallback } from 'react'
import { useWsTopic } from './useWsTopic'
import { liquidationDestination, type WsLiquidation } from '@/types/ws'

/**
 * useLiquidationTopic — 订阅强平事件 `/topic/liquidations/{userId}`,收到消息调 onLiquidation。
 *
 * destination 由 liquidationDestination(userId) 派生(userId 为 null 时不订阅,
 * 用户未登录时静默;就绪后自动订阅)。底层复用 useWsTopic(通用 STOMP 订阅 hook),
 * 由 ConnectionManager 在 onConnect 重连后补订阅(broker 不持久化离线消息)。
 *
 * 用法(3.4/3.5 业务页移植时挂):
 * ```ts
 * useLiquidationTopic(userId, (liq) => {
 *   toast.error(`持仓 #${liq.positionId} 被强平,已实现盈亏 ${formatMoney(liq.realizedPnl)}`)
 *   refetchPositions()
 * })
 * ```
 *
 * 限制(与 useWsTopic 一致):同一 destination 全局只能一个订阅(Map key = destination)。
 * 多组件订阅同 userId 会互相覆盖 —— 应在 store 层订阅一次,组件读 store。
 *
 * @param userId 当前登录用户 ID;未登录传 null/undefined 静默不订阅
 * @param onLiquidation 强平事件回调(payload 已 JSON.parse,字段对齐 WsLiquidation)
 */
export function useLiquidationTopic(
  userId: number | string | null | undefined,
  onLiquidation: (liq: WsLiquidation) => void,
): void {
  const destination = userId != null && userId !== '' ? liquidationDestination(userId) : null
  // handler 用 ref 持有最新闭包(useWsTopic 内部已处理),这里包一层类型断言收窄 unknown → WsLiquidation
  const handler = useCallback(
    (payload: unknown) => {
      onLiquidation(payload as WsLiquidation)
    },
    [onLiquidation],
  )
  useWsTopic(destination, handler)
}
