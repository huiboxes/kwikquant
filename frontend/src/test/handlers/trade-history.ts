import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * trade-history MSW handlers。
 * mock 数据照原型 AppContext.jsx 的 trades(6 条)适配 TradeHistoryDto 字段。
 * accountId 约定:1 = PAPER 主模拟盘,2 = LIVE 主账户(对应原型 acc 字段)。
 * side 用后端小写(buy/sell),原型大写(BUY/SELL)在 page port 时转换。
 */
type TradeHistoryDto = components['schemas']['TradeHistoryDto']

const TRADES: TradeHistoryDto[] = [
  { orderId: 9001, accountId: 1, symbol: 'BTC/USDT', side: 'buy', orderType: 'limit', amount: 0.42, filledQty: 0.42, filledAvgPrice: 61200, totalFee: 6.12, totalVolume: 25704, status: 'FILLED', createdAt: '2026-07-09T14:02:18Z', updatedAt: '2026-07-09T14:02:18Z' },
  { orderId: 9002, accountId: 2, symbol: 'SOL/USDT', side: 'buy', orderType: 'market', amount: 8, filledQty: 8, filledAvgPrice: 142.6, totalFee: 0.34, totalVolume: 1140.8, status: 'FILLED', createdAt: '2026-07-09T13:58:42Z', updatedAt: '2026-07-09T13:58:42Z' },
  { orderId: 9003, accountId: 1, symbol: 'ETH/USDT', side: 'sell', orderType: 'limit', amount: 2.0, filledQty: 2.0, filledAvgPrice: 3142.18, totalFee: 3.14, totalVolume: 6284.36, status: 'FILLED', createdAt: '2026-07-09T13:30:00Z', updatedAt: '2026-07-09T13:30:00Z' },
  { orderId: 9004, accountId: 1, symbol: 'BTC/USDT', side: 'sell', orderType: 'limit', amount: 0.1, filledQty: 0.1, filledAvgPrice: 60800, totalFee: 3.04, totalVolume: 6080, status: 'FILLED', createdAt: '2026-07-08T22:18:09Z', updatedAt: '2026-07-08T22:18:09Z' },
  { orderId: 9005, accountId: 2, symbol: 'BTC/USDT', side: 'buy', orderType: 'limit', amount: 0.05, filledQty: 0.05, filledAvgPrice: 59800, totalFee: 1.5, totalVolume: 2990, status: 'FILLED', createdAt: '2026-07-08T18:42:30Z', updatedAt: '2026-07-08T18:42:30Z' },
  { orderId: 9006, accountId: 1, symbol: 'SOL/USDT', side: 'sell', orderType: 'limit', amount: 12, filledQty: 12, filledAvgPrice: 138.2, totalFee: 0.66, totalVolume: 1658.4, status: 'FILLED', createdAt: '2026-07-08T10:22:11Z', updatedAt: '2026-07-08T10:22:11Z' },
]

const STATS = {
  totalVolume: TRADES.reduce((a, t) => a + t.totalVolume, 0),
  totalFees: TRADES.reduce((a, t) => a + t.totalFee, 0),
  // realizedPnl 后端聚合算(成交额 - 成本),mock 简化用固定值对齐原型 +24.40+180-42.10≈162.30
  realizedPnl: 162.3,
}

export const tradeHistoryHandlers = [
  http.get('/api/v1/trade-history', ({ request }) => {
    const url = new URL(request.url)
    const page = Math.max(1, parseInt(url.searchParams.get('page') ?? '1', 10))
    const pageSize = Math.max(1, parseInt(url.searchParams.get('pageSize') ?? '10', 10))
    const accountId = url.searchParams.get('accountId')
    const symbol = url.searchParams.get('symbol')

    let filtered = TRADES
    if (accountId && accountId !== 'all') filtered = filtered.filter((t) => String(t.accountId) === accountId)
    if (symbol && symbol !== 'all') filtered = filtered.filter((t) => t.symbol.includes(symbol))

    const total = filtered.length
    const totalPages = Math.ceil(total / pageSize) || 1
    const content = filtered.slice((page - 1) * pageSize, page * pageSize)

    return HttpResponse.json(
      envelope({ content, page, pageSize, total, totalPages }),
    )
  }),

  http.get('/api/v1/trade-history/stats', ({ request }) => {
    const url = new URL(request.url)
    const accountId = url.searchParams.get('accountId')
    const filtered = accountId && accountId !== 'all' ? TRADES.filter((t) => String(t.accountId) === accountId) : TRADES
    return HttpResponse.json(
      envelope({
        totalVolume: filtered.reduce((a, t) => a + t.totalVolume, 0),
        totalFees: filtered.reduce((a, t) => a + t.totalFee, 0),
        realizedPnl: STATS.realizedPnl,
      }),
    )
  }),
]
