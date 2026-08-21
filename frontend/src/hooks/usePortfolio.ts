import { useQuery } from '@tanstack/react-query'
import {
  fetchPortfolioSummary,
  fetchPortfolioPnl,
  fetchPortfolioEquityCurve,
} from '@/api/portfolio'
import { portfolioKeys } from '@/api/_queryKeys'
import { useWsStore } from '@/stores/wsStore'

/**
 * WS 断连兜底轮询:connected 时不轮询(推送驱动 invalidate)，断连/降级时 15s 拉一次，
 * 避免弱网下组合数据永久 stale。
 */
const wsFallbackPoll = () => (useWsStore.getState().status === 'connected' ? false : 15_000)

/** usePortfolioSummary — 多账户余额聚合。mode: 'PAPER'|'LIVE'|undefined。 */
export function usePortfolioSummary(mode?: string) {
  return useQuery({
    queryKey: portfolioKeys.summary(mode),
    queryFn: () => fetchPortfolioSummary(mode),
    refetchInterval: wsFallbackPoll,
  })
}

/** usePortfolioPnl — 未实现盈亏。mode 语义同 summary。 */
export function usePortfolioPnl(mode?: string) {
  return useQuery({
    queryKey: portfolioKeys.pnl(mode),
    queryFn: () => fetchPortfolioPnl(mode),
    refetchInterval: wsFallbackPoll,
  })
}

/** usePortfolioEquityCurve — 权益曲线。mode 语义同 summary。 */
export function usePortfolioEquityCurve(mode?: string) {
  return useQuery({
    queryKey: portfolioKeys.equityCurve(mode),
    queryFn: () => fetchPortfolioEquityCurve(mode),
  })
}
