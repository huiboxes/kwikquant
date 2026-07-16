import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * portfolio typed client。
 *
 * 端点(均 JWT):
 *  - GET /api/v1/portfolio/summary?mode=PAPER|LIVE → PortfolioSummary
 *  - GET /api/v1/portfolio/pnl?mode=PAPER|LIVE     → PortfolioPnl
 *  - GET /api/v1/portfolio/equity-curve?mode=PAPER|LIVE → EquityPointDto[]
 */
type PortfolioSummary = components['schemas']['PortfolioSummary']
type PortfolioPnl = components['schemas']['PortfolioPnl']
type EquityPointDto = components['schemas']['EquityPointDto']

/** 组合总览。mode: PAPER=仅模拟盘, LIVE=仅实盘, undefined=仅实盘(向后兼容)。 */
export function fetchPortfolioSummary(mode?: string): Promise<PortfolioSummary> {
  const qs = mode ? `?mode=${encodeURIComponent(mode)}` : ''
  return apiFetch<PortfolioSummary>(`/api/v1/portfolio/summary${qs}`)
}

/** 持仓未实现盈亏。mode 语义同 summary。 */
export function fetchPortfolioPnl(mode?: string): Promise<PortfolioPnl> {
  const qs = mode ? `?mode=${encodeURIComponent(mode)}` : ''
  return apiFetch<PortfolioPnl>(`/api/v1/portfolio/pnl${qs}`)
}

/** 组合权益曲线。mode 语义同 summary。 */
export function fetchPortfolioEquityCurve(mode?: string): Promise<EquityPointDto[]> {
  const qs = mode ? `?mode=${encodeURIComponent(mode)}` : ''
  return apiFetch<EquityPointDto[]>(`/api/v1/portfolio/equity-curve${qs}`)
}
