import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * position typed client。
 *
 * 端点(均 JWT):
 *  - GET /api/v1/positions?accountId&symbol? → PositionDto[](1002 越权)
 *
 * honest(TD-040):PositionDto 无 uPnl/currentPrice(只有 realizedPnl),
 * 原型 PositionsTable 的 uPnl 列显示 "—"。realizedPnl 用 toDecimal + formatMoney 显示。
 * PortfolioPnl.PositionPnl(跨账户聚合)含 uPnl,但 TradingPage 是单账户视角用 /positions。
 */
type PositionDto = components['schemas']['PositionDto']

export type { PositionDto }

/** 查询持仓(GET /positions?accountId=&symbol=)。accountId 鉴权校验归属,越权 403(1002)。 */
export function fetchPositions(accountId: number, symbol?: string): Promise<PositionDto[]> {
  const qs = new URLSearchParams({ accountId: String(accountId) })
  if (symbol) qs.set('symbol', symbol)
  return apiFetch<PositionDto[]>(`/api/v1/positions?${qs.toString()}`)
}
