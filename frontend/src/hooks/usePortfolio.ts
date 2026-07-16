import { useQuery } from '@tanstack/react-query'
import {
  fetchPortfolioSummary,
  fetchPortfolioPnl,
  fetchPortfolioEquityCurve,
} from '@/api/portfolio'
import { portfolioKeys } from '@/api/_queryKeys'

/** usePortfolioSummary — 多账户余额聚合。mode: 'PAPER'|'LIVE'|undefined。 */
export function usePortfolioSummary(mode?: string) {
  return useQuery({
    queryKey: portfolioKeys.summary(mode),
    queryFn: () => fetchPortfolioSummary(mode),
  })
}

/** usePortfolioPnl — 未实现盈亏。mode 语义同 summary。 */
export function usePortfolioPnl(mode?: string) {
  return useQuery({
    queryKey: portfolioKeys.pnl(mode),
    queryFn: () => fetchPortfolioPnl(mode),
  })
}

/** usePortfolioEquityCurve — 权益曲线。mode 语义同 summary。 */
export function usePortfolioEquityCurve(mode?: string) {
  return useQuery({
    queryKey: portfolioKeys.equityCurve(mode),
    queryFn: () => fetchPortfolioEquityCurve(mode),
  })
}
