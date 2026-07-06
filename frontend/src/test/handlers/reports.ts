import { HttpResponse, http } from 'msw'
import type { BacktestReportDetailDto } from '@/hooks/useBacktestReport'

/**
 * reports 端点 MSW handler(spec §5 step 20-22,批 1b 测试用)。
 *
 * GET /api/v1/reports/:id → BacktestReportDetailDto(metrics/trades/equityCurve)。
 *
 * reportId=42 对应 backtests handler COMPLETED 任务的 reportId。
 * 后端 ApiResponse envelope:{code:0, message, data}。
 */

const REPORT: BacktestReportDetailDto = {
  id: 42,
  name: 'BTC/USDT 1h 回测',
  symbol: 'BTC/USDT',
  timeframe: '1h',
  periodStart: '2026-06-01T00:00:00Z',
  periodEnd: '2026-07-01T00:00:00Z',
  params: JSON.stringify({ initial_capital: 10000 }),
  metrics: {
    totalReturn: 0.1532,
    sharpeRatio: 1.85,
    maxDrawdown: -0.0842,
    winRate: 0.62,
    profitFactor: 2.1,
    totalTrades: 128,
    avgTradeDurationSeconds: 3600,
  },
  trades: [
    {
      id: 1024,
      time: '2026-06-15T08:30:00Z',
      side: 'buy',
      price: 42150.5,
      amount: 0.0025,
      fee: 0.0052,
    },
    {
      id: 1025,
      time: '2026-06-16T14:00:00Z',
      side: 'sell',
      price: 43120.8,
      amount: 0.0025,
      fee: 0.0053,
    },
  ],
  equityCurve: [
    { time: '2026-06-01T00:00:00Z', equity: 10000 },
    { time: '2026-06-15T08:30:00Z', equity: 10052.18 },
    { time: '2026-06-16T14:00:00Z', equity: 10532.18 },
    { time: '2026-07-01T00:00:00Z', equity: 11532.18 },
  ],
  source: 'BACKTEST',
  createdAt: '2026-07-04T12:01:00Z',
  updatedAt: '2026-07-04T12:01:00Z',
}

export const reportHandlers = [
  http.get('/api/v1/reports/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    if (id !== 42) {
      return HttpResponse.json(
        { code: 9001, message: '报告不存在', data: null },
        { status: 404 },
      )
    }
    return HttpResponse.json({ code: 0, message: 'ok', data: REPORT })
  }),
]
