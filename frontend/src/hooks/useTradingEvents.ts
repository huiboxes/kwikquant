import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { orderKeys, positionKeys, portfolioKeys, accountKeys } from '@/api/_queryKeys'
import { useWsStore } from '@/stores/wsStore'

/**
 * useTradingEvents — 全局订阅 trading/portfolio WS 主题，收到事件 invalidate 对应 queryKeys。
 *
 * AppLayout 调一次(全局接线)。各页面 useOrders/usePositions/useOrderFills/usePortfolio*
 * 的 react-query 被 invalidate 后自动重拉最新数据，无需 mutation 手动 refetch。
 *
 * - /topic/orders/{userId}(OrderEvent):invalidate 订单列表
 * - /topic/fills/{userId}(FillEvent):invalidate 订单列表 + 该 orderId 的 fills
 * - /topic/positions/{userId}(PositionEvent):invalidate 持仓列表
 * - /topic/portfolio/{userId}(PortfolioEvent):invalidate 组合 summary/pnl/equityCurve
 *
 * 弥补 mutation 成功后 invalidate 的局限:WS 实时推送也能刷新，如外部/Worker 触发的成交。
 */
export function useTradingEvents(userId: number | null) {
  const qc = useQueryClient()

  const orderTopic = userId != null ? `/topic/orders/${userId}` : null
  const fillTopic = userId != null ? `/topic/fills/${userId}` : null
  const positionTopic = userId != null ? `/topic/positions/${userId}` : null
  const portfolioTopic = userId != null ? `/topic/portfolio/${userId}` : null

  useWsTopic(orderTopic, () => {
    qc.invalidateQueries({ queryKey: orderKeys.all })
    // 订单状态变(下单冻结 / 撤单释放 / 成交)影响余额，invalidate balance 让 BalanceBar 刷新。
    qc.invalidateQueries({ queryKey: accountKeys.all })
    // 订单状态变影响组合(持仓/余额变 → 权益/累计盈亏),invalidate portfolio 让 Dashboard 刷新。
    qc.invalidateQueries({ queryKey: portfolioKeys.all })
  })

  useWsTopic(fillTopic, (payload) => {
    qc.invalidateQueries({ queryKey: orderKeys.all })
    // 成交影响余额(释放冻结 / 扣手续费 / PnL 入账),invalidate balance 让 BalanceBar 刷新，
    // 否则用户需手动刷新页面才看到冻结额变化。
    qc.invalidateQueries({ queryKey: accountKeys.all })
    // 成交影响组合权益(余额/PnL 变),invalidate portfolio 让权益曲线/累计盈亏刷新。
    qc.invalidateQueries({ queryKey: portfolioKeys.all })
    const ev = payload as { orderId?: number }
    if (ev.orderId != null) {
      qc.invalidateQueries({ queryKey: orderKeys.fills(ev.orderId) })
    }
  })

  useWsTopic(positionTopic, () => {
    qc.invalidateQueries({ queryKey: positionKeys.all })
    // 持仓变影响组合(未实现 PnL / 权益),invalidate portfolio 让 Dashboard 刷新。
    qc.invalidateQueries({ queryKey: portfolioKeys.all })
  })

  useWsTopic(portfolioTopic, () => {
    qc.invalidateQueries({ queryKey: portfolioKeys.all })
  })

  // WS 断连窗口(降级轮询期)可能漏推送；重连成功瞬间统一 invalidate 拉最新，补齐断连期变化。
  const wsStatus = useWsStore((s) => s.status)
  const prevStatusRef = useRef(wsStatus)
  useEffect(() => {
    const prev = prevStatusRef.current
    prevStatusRef.current = wsStatus
    if (wsStatus === 'connected' && prev !== 'connected' && prev !== 'idle') {
      qc.invalidateQueries({ queryKey: orderKeys.all })
      qc.invalidateQueries({ queryKey: positionKeys.all })
      qc.invalidateQueries({ queryKey: portfolioKeys.all })
      qc.invalidateQueries({ queryKey: accountKeys.all })
    }
  }, [wsStatus, qc])
}
