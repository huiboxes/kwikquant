import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * position typed client。
 *
 * 端点(均 JWT):
 *  - GET  /api/v1/positions?accountId&symbol? → PositionDto[](1002 越权)
 *  - POST /api/v1/positions/{positionId}/close → ApiResponseVoid(反向市价单平仓,FLAT/不存在 404/4001)
 *
 * TD-040 已接:PositionDto.unrealizedPnl + currentPrice(行情不可用为 null,见 api-gen 注释)。
 * TradingPage PositionsTable 单行 uPnl 用 unrealizedPnl(null 显 —);BalanceBar 单账户聚合用 sumUnrealizedPnl(positions)。
 * PortfolioPnl.PositionPnl(跨账户聚合)含 uPnl,PortfolioPage 用 /portfolio/pnl;TradingPage 单账户视角用 /positions。
 */
type PositionDto = components['schemas']['PositionDto']

export type { PositionDto }

/** 查询持仓(GET /positions?accountId=&symbol=)。accountId 鉴权校验归属,越权 403(1002)。 */
export function fetchPositions(accountId: number, symbol?: string): Promise<PositionDto[]> {
  const qs = new URLSearchParams({ accountId: String(accountId) })
  if (symbol) qs.set('symbol', symbol)
  return apiFetch<PositionDto[]>(`/api/v1/positions?${qs.toString()}`)
}

/**
 * 平仓(POST /positions/{positionId}/close,反向市价单平掉指定持仓全部数量)。
 * 走完整下单链路(风控+余额冻结+路由)。FLAT/不存在返 404(4001)。
 * apiFetch unwrap envelope:200 成功 data=null,202 返 OrderSubmitResult(异步下单);
 * 前端只关心成功/失败,标注 void。TD-044 接入:TradingPage 平仓按钮 + ConfirmDialog。
 */
export function closePosition(positionId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/positions/${positionId}/close`, {
    method: 'POST',
  })
}
