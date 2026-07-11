import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * strategy MSW handlers(本任务只建 list + stop,其他端点留 StrategyPage 任务)。
 * mock 数据照原型 AppContext.jsx strategies 适配 StrategyDetailDto。
 * brief 指定:至少 4 条混合 status,含 3 个 RUNNING 供紧急停止测。
 * name/symbol 对齐原型(BTC Trend Rider / ETH Mean Reversion / SOL 做市 / Grid Scalper / Funding Arb)。
 */
type StrategyDetailDto = components['schemas']['StrategyDetailDto']

const STRATEGIES: StrategyDetailDto[] = [
  {
    id: 1,
    name: 'BTC Trend Rider',
    description: '双均线突破 + ATR 止损',
    symbol: 'BTC/USDT',
    exchange: 'BINANCE',
    marketType: 'SPOT',
    intervalValue: '15m',
    status: 'RUNNING',
    parameters: '{}',
    createdAt: '2026-07-01T08:00:00Z',
    updatedAt: '2026-07-09T12:00:00Z',
  },
  {
    id: 2,
    name: 'ETH Mean Reversion',
    description: 'Z-score 反转,布林带过滤',
    symbol: 'ETH/USDT',
    exchange: 'BINANCE',
    marketType: 'SPOT',
    intervalValue: '5m',
    status: 'RUNNING',
    parameters: '{}',
    createdAt: '2026-07-02T08:00:00Z',
    updatedAt: '2026-07-09T12:00:00Z',
  },
  {
    id: 3,
    name: 'SOL 做市',
    description: '网格挂单 + 资金费率套利',
    symbol: 'SOL/USDT',
    exchange: 'OKX',
    marketType: 'SPOT',
    intervalValue: '1m',
    status: 'RUNNING',
    parameters: '{"gridNum":10}',
    createdAt: '2026-07-03T08:00:00Z',
    updatedAt: '2026-07-09T12:00:00Z',
  },
  {
    id: 4,
    name: 'Grid Scalper',
    description: '网格挂单 + 资金费率套利',
    symbol: 'SOL/USDT',
    exchange: 'OKX',
    marketType: 'SPOT',
    intervalValue: '1m',
    status: 'PAUSED',
    parameters: '{"gridNum":10}',
    createdAt: '2026-07-03T08:00:00Z',
    updatedAt: '2026-07-08T18:00:00Z',
  },
  {
    id: 5,
    name: 'Funding Arb',
    description: '资金费率套利,未发布',
    symbol: 'BTC/USDT-PERP',
    exchange: 'BITGET',
    marketType: 'PERP',
    intervalValue: '1h',
    status: 'DRAFT',
    parameters: '{}',
    createdAt: '2026-07-04T08:00:00Z',
    updatedAt: '2026-07-04T08:00:00Z',
  },
]

export const strategyHandlers = [
  // GET /api/v1/strategies → 当前用户策略列表
  http.get('/api/v1/strategies', () => {
    return HttpResponse.json(envelope(STRATEGIES))
  }),

  // POST /api/v1/strategies/{id}/stop → 停止(RUNNING/PAUSED/ERROR→STOPPED)
  http.post('/api/v1/strategies/:id/stop', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const s = STRATEGIES.find((x) => x.id === id)
    if (!s) {
      return HttpResponse.json(envelope(null, 7004, '策略不存在'), { status: 404 })
    }
    // RUNNING/PAUSED/ERROR 可转移 STOPPED;DRAFT/READY/STOPPED 不可 → 409(7002)
    if (s.status !== 'RUNNING' && s.status !== 'PAUSED' && s.status !== 'ERROR') {
      return HttpResponse.json(envelope(null, 7002, `状态 ${s.status} 不可转移到 STOPPED`), {
        status: 409,
      })
    }
    s.status = 'STOPPED'
    s.updatedAt = new Date().toISOString()
    return HttpResponse.json(envelope(s))
  }),
]
