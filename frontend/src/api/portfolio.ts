import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * portfolio typed client。
 *
 * 端点(均 JWT):
 *  - GET /api/v1/portfolio/summary       → PortfolioSummary(多账户余额 USDT 估值,部分失败降级返成功子集)
 *  - GET /api/v1/portfolio/pnl           → PortfolioPnl(未实现盈亏,含 positions PositionPnl[] + totalUnrealizedPnl)
 *  - GET /api/v1/portfolio/equity-curve  → EquityPointDto[](⚠ honest:后端无此端点,MSW mock 占位)
 *
 * honest:equityCurve 字段只在回测报告 schema(api-gen line 1718/3347),portfolio/pnl 无曲线端点,
 * 只返单点 totalUnrealizedPnl。PortfolioPage 的 EquityCurve 图表走 mock 端点,
 * 后续真端点上线只需改 handler + URL,page 不变。
 */
type PortfolioSummary = components['schemas']['PortfolioSummary']
type PortfolioPnl = components['schemas']['PortfolioPnl']
type EquityPointDto = components['schemas']['EquityPointDto']

/** 组合总览(多账户余额聚合,部分失败降级返成功子集;全部失败 502/6001)。 */
export function fetchPortfolioSummary(): Promise<PortfolioSummary> {
  return apiFetch<PortfolioSummary>('/api/v1/portfolio/summary')
}

/** 持仓未实现盈亏(含 positions PositionPnl[] + totalUnrealizedPnl 单点)。 */
export function fetchPortfolioPnl(): Promise<PortfolioPnl> {
  return apiFetch<PortfolioPnl>('/api/v1/portfolio/pnl')
}

/**
 * 组合权益曲线(⚠ honest:后端无此端点,MSW mock 占位)。
 * 待后端补 /portfolio/equity-curve 端点 or 澄清来源。当前走 mock handler。
 */
export function fetchPortfolioEquityCurve(): Promise<EquityPointDto[]> {
  return apiFetch<EquityPointDto[]>('/api/v1/portfolio/equity-curve')
}
