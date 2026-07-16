import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * trade-history typed client。
 *
 * 端点(均 GET + JWT):
 *  - GET /api/v1/trade-history         分页查询(聚合多账户订单+成交,按订单维度)→ PageDtoTradeHistoryDto
 *  - GET /api/v1/trade-history/stats   统计(成交额/累计手续费/已实现盈亏)→ TradeHistoryStatsDto
 *  - GET /api/v1/trade-history/export  导出 CSV/JSON 文件流
 *
 * 金额字段(amount/filledQty/filledAvgPrice/totalFee/totalVolume/totalFees/realizedPnl)
 * api-gen 标 number(springdoc BigDecimal 局限),后端运行时序列化为 string;前端用 decimal.js 接收(toDecimal 兼容)。
 */
type PageDtoTradeHistoryDto = components['schemas']['PageDtoTradeHistoryDto']
type TradeHistoryStatsDto = components['schemas']['TradeHistoryStatsDto']

export interface TradeHistoryQuery {
  page?: number
  pageSize?: number
  accountId?: number
  symbol?: string
  startTime?: string
  endTime?: string
}

export interface TradeHistoryStatsQuery {
  accountId?: number
  startTime?: string
  endTime?: string
  mode?: string
}

function toQs(params: object): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v != null && (typeof v === 'string' || typeof v === 'number')) qs.set(k, String(v))
  }
  const s = qs.toString()
  return s ? `?${s}` : ''
}

/** 分页查询交易历史。accountId 为空 = 当前用户全部账户。 */
export function fetchTradeHistory(params: TradeHistoryQuery = {}): Promise<PageDtoTradeHistoryDto> {
  return apiFetch<PageDtoTradeHistoryDto>(`/api/v1/trade-history${toQs(params)}`)
}

/** 交易统计(成交额/累计手续费/已实现盈亏,按账户+时间范围聚合)。 */
export function fetchTradeHistoryStats(params: TradeHistoryStatsQuery = {}): Promise<TradeHistoryStatsDto> {
  return apiFetch<TradeHistoryStatsDto>(`/api/v1/trade-history/stats${toQs(params)}`)
}

/**
 * 导出交易历史(CSV/JSON 文件流)。
 * 注意:返回文件流非 envelope,不能用 apiFetch(parseBody 强制 json);用裸 fetch 带 Bearer。
 * TODO:文件下载 + auth header + 文件名解析(后端 Content-Disposition),当前 HistoryPage 导出按钮走 toast 提示暂未调用。
 */
export async function exportTradeHistory(
  params: TradeHistoryQuery & { format?: 'csv' | 'json' } = {},
): Promise<Blob> {
  const { format, ...rest } = params
  const qs = toQs({ ...rest, format })
  const res = await fetch(`/api/v1/trade-history/export${qs}`)
  if (!res.ok) throw new Error(`export failed: ${res.status}`)
  return res.blob()
}
