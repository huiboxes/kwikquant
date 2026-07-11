import { useQuery } from '@tanstack/react-query'
import {
  fetchPortfolioSummary,
  fetchPortfolioPnl,
  fetchPortfolioEquityCurve,
} from '@/api/portfolio'
import { portfolioKeys } from '@/api/_queryKeys'

/** usePortfolioSummary — 多账户余额聚合(USDT 估值,部分失败降级返成功子集)。 */
export function usePortfolioSummary() {
  return useQuery({
    queryKey: portfolioKeys.summary(),
    queryFn: fetchPortfolioSummary,
  })
}

/** usePortfolioPnl — 未实现盈亏(含 positions PositionPnl[] + totalUnrealizedPnl)。 */
export function usePortfolioPnl() {
  return useQuery({
    queryKey: portfolioKeys.pnl(),
    queryFn: fetchPortfolioPnl,
  })
}

/** usePortfolioEquityCurve — 权益曲线(⚠ honest:后端无端点,走 MSW mock 占位)。 */
export function usePortfolioEquityCurve() {
  return useQuery({
    queryKey: portfolioKeys.equityCurve(),
    queryFn: fetchPortfolioEquityCurve,
  })
}
