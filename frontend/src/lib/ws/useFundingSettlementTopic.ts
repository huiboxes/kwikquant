import { useCallback } from 'react'
import { useWsTopic } from './useWsTopic'
import { fundingSettlementDestination, type WsFundingSettlement } from '@/types/ws'

/**
 * useFundingSettlementTopic — 订阅资金费率结算事件 `/topic/funding/{userId}`,收到消息调 onSettlement。
 *
 * destination 由 fundingSettlementDestination(userId) 派生(userId 为 null 时不订阅,
 * 用户未登录时静默;就绪后自动订阅)。底层复用 useWsTopic(通用 STOMP 订阅 hook),
 * 由 ConnectionManager 在 onConnect 重连后补订阅(broker 不持久化离线消息)。
 *
 * 用法(实盘 PERP 资金费率结算通知):
 * ```ts
 * useFundingSettlementTopic(userId, (s) => {
 *   const amt = toDecimal(s.fundingAmount)
 *   toast.info(`资金费率已结算 ${formatMoney(amt)}`)
 *   refetchPositions()
 * })
 * ```
 *
 * 限制(与 useWsTopic 一致):同一 destination 全局只能一个订阅(Map key = destination)。
 * 多组件订阅同 userId 会互相覆盖 —— 应在 store 层订阅一次,组件读 store。
 *
 * @param userId 当前登录用户 ID;未登录传 null/undefined 静默不订阅
 * @param onSettlement 资金费率结算事件回调(payload 已 JSON.parse,字段对齐 WsFundingSettlement)
 */
export function useFundingSettlementTopic(
  userId: number | string | null | undefined,
  onSettlement: (s: WsFundingSettlement) => void,
): void {
  const destination =
    userId != null && userId !== '' ? fundingSettlementDestination(userId) : null
  // handler 用 ref 持有最新闭包(useWsTopic 内部已处理),这里包一层类型断言收窄 unknown → WsFundingSettlement
  const handler = useCallback(
    (payload: unknown) => {
      onSettlement(payload as WsFundingSettlement)
    },
    [onSettlement],
  )
  useWsTopic(destination, handler)
}
