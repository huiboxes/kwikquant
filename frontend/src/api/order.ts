import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'
import type { OrderStatus } from '@/components/OrderStatusBadge'

/**
 * order typed client。
 *
 * 端点(均 JWT):
 *  - POST   /api/v1/orders                       → OrderSubmitResult(201)/ 风控拒 200+code=4105
 *  - GET    /api/v1/orders?accountId&symbol&...  → PageDtoOrderDetailDto(分页)
 *  - DELETE /api/v1/orders/{orderId}             → 202 + OrderCancelResult(4101 不可撤 / 4107 并发冲突)
 *  - GET    /api/v1/orders/{orderId}/fills       → FillDto[](4001 不存在)
 *
 * honest 契约差异(见 task-tradingpage-brief.md):
 *  - TD-039:OrderDetailDto.status 6 态(NEW|PARTIAL|FILLED|CANCELLED|REJECTED|EXPIRED),
 *    OrderStatusBadge 9 态 ws 命名(PARTIALLY_FILLED/CANCELED)。normalizeOrderStatus 映射。
 *  - TD-041:风控拒 4105 HTTP 200 + body code=4105(非 HTTP 错误)。apiFetch parseBody 抛 ApiError(4105),
 *    组件 useSubmitOrder onError 检查 e.code===4105 → toast + navigate('/risk')。
 *  - OrderDetailDto.side/orderType 小写(buy|sell, limit),展示大写化(sideLabel/orderTypeLabel)。
 */
type OrderSubmitRequest = components['schemas']['OrderSubmitRequest']
type OrderSubmitResult = components['schemas']['OrderSubmitResult']
type OrderDetailDto = components['schemas']['OrderDetailDto']
type PageDtoOrderDetailDto = components['schemas']['PageDtoOrderDetailDto']
type OrderCancelResult = components['schemas']['OrderCancelResult']
type FillDto = components['schemas']['FillDto']
type OrderListQuery = components['schemas']['OrderListQuery']

export type {
  OrderSubmitRequest,
  OrderSubmitResult,
  OrderDetailDto,
  PageDtoOrderDetailDto,
  OrderCancelResult,
  FillDto,
  OrderListQuery,
}

/** 订单列表查询参数(accountId 必填,其余可选传空串=不过滤)。 */
export interface OrderListParams {
  accountId: number
  symbol?: string
  status?: string
  startTime?: string
  endTime?: string
  page?: number
  pageSize?: number
}

/** 分页查询订单(GET /orders?accountId=&symbol=&status=&startTime=&endTime=&page=&pageSize=)。 */
export function fetchOrders(params: OrderListParams): Promise<PageDtoOrderDetailDto> {
  const qs = new URLSearchParams()
  qs.set('accountId', String(params.accountId))
  if (params.symbol) qs.set('symbol', params.symbol)
  if (params.status) qs.set('status', params.status)
  if (params.startTime) qs.set('startTime', params.startTime)
  if (params.endTime) qs.set('endTime', params.endTime)
  if (params.page != null) qs.set('page', String(params.page))
  if (params.pageSize != null) qs.set('pageSize', String(params.pageSize))
  return apiFetch<PageDtoOrderDetailDto>(`/api/v1/orders?${qs.toString()}`)
}

/** 查成交明细(GET /orders/{orderId}/fills)。订单不存在 404(4001)。 */
export function listFills(orderId: number): Promise<FillDto[]> {
  return apiFetch<FillDto[]>(`/api/v1/orders/${orderId}/fills`)
}

/** 撤单(DELETE /orders/{orderId} → 202 + OrderCancelResult)。已成交 422(4101)/ 并发冲突 409(4107)。 */
export function cancelOrder(orderId: number): Promise<OrderCancelResult> {
  return apiFetch<OrderCancelResult>(`/api/v1/orders/${orderId}`, { method: 'DELETE' })
}

/** 提交订单(POST /orders → 201 + OrderSubmitResult)。风控拒 200+code=4105 → apiFetch 抛 ApiError(4105)。 */
export function submitOrder(body: OrderSubmitRequest): Promise<OrderSubmitResult> {
  return apiFetch<OrderSubmitResult>('/api/v1/orders', { method: 'POST', body })
}

/**
 * TD-039:OrderDetailDto.status(6 态 NEW|PARTIAL|FILLED|CANCELLED|REJECTED|EXPIRED)
 * → OrderStatusBadge(9 态 ws 命名)。PARTIAL→PARTIALLY_FILLED, CANCELLED→CANCELED,其余同名。
 * 未知值原样透传(badge fallback neutral)。
 */
export function normalizeOrderStatus(dtoStatus: string): OrderStatus | string {
  switch (dtoStatus) {
    case 'PARTIAL':
      return 'PARTIALLY_FILLED'
    case 'CANCELLED':
      return 'CANCELED'
    default:
      return dtoStatus as OrderStatus
  }
}

/** 方向标签(OrderDetailDto.side 小写 → 大写中文)。 */
export function sideLabel(side: string): string {
  switch (side.toUpperCase()) {
    case 'BUY':
      return '买入'
    case 'SELL':
      return '卖出'
    default:
      return side
  }
}

/** 订单类型标签(小写 → 大写展示)。 */
export function orderTypeLabel(orderType: string): string {
  return orderType.toUpperCase()
}

/** 有效期标签。 */
export function tifLabel(tif: string): string {
  return tif.toUpperCase()
}
