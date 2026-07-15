import { useQueryClient } from '@tanstack/react-query'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { orderKeys, positionKeys, portfolioKeys } from '@/api/_queryKeys'

/**
 * useTradingEvents — 全局订阅 trading/portfolio WS 主题,收到事件 invalidate 对应 queryKeys。
 *
 * AppLayout 调一次(全局接线)。各页面 useOrders/usePositions/useOrderFills/usePortfolio*
 * 的 react-query 被 invalidate 后自动重拉最新数据,无需 mutation 手动 refetch。
 *
 * - /topic/orders/{userId}(OrderEvent):invalidate 订单列表
 * - /topic/fills/{userId}(FillEvent):invalidate 订单列表 + 该 orderId 的 fills
 * - /topic/positions/{userId}(PositionEvent):invalidate 持仓列表
 * - /topic/portfolio/{userId}(PortfolioEvent):invalidate 组合 summary/pnl/equityCurve
 *
 * 替代 TD-046(mutation 成功后 invalidate 的局限:WS 实时推送也能刷新,如外部/Worker 触发的成交)。
 */
export function useTradingEvents(userId: number | null) {
  const qc = useQueryClient()

  const orderTopic = userId != null ? `/topic/orders/${userId}` : null
  const fillTopic = userId != null ? `/topic/fills/${userId}` : null
  const positionTopic = userId != null ? `/topic/positions/${userId}` : null
  const portfolioTopic = userId != null ? `/topic/portfolio/${userId}` : null

  useWsTopic(orderTopic, () => {
    qc.invalidateQueries({ queryKey: orderKeys.all })
  })

  useWsTopic(fillTopic, (payload) => {
    qc.invalidateQueries({ queryKey: orderKeys.all })
    const ev = payload as { orderId?: number }
    if (ev.orderId != null) {
      qc.invalidateQueries({ queryKey: orderKeys.fills(ev.orderId) })
    }
  })

  useWsTopic(positionTopic, () => {
    qc.invalidateQueries({ queryKey: positionKeys.all })
  })

  useWsTopic(portfolioTopic, () => {
    qc.invalidateQueries({ queryKey: portfolioKeys.all })
  })
}
