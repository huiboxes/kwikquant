import { HttpResponse, http } from 'msw'

/**
 * strategies 端点 MSW handler(spec §5 step 6,批 1a 测试用)。
 * 后端 ApiResponse envelope:{code:0, message, data}。
 */

interface MockStrategy {
  id: number
  name: string
  description: string
  status: string
  exchange: string
  symbol: string
  ownerId: number
  createdAt: string
  updatedAt: string
}

const STRATEGIES: MockStrategy[] = [
  {
    id: 1,
    name: 'BTC 网格',
    description: 'BTC/USDT 网格策略',
    status: 'DRAFT',
    exchange: 'BINANCE',
    symbol: 'BTC/USDT',
    ownerId: 42,
    createdAt: '2026-07-05T10:00:00Z',
    updatedAt: '2026-07-05T10:00:00Z',
  },
  {
    id: 2,
    name: 'ETH 动量',
    description: 'ETH/USDT 趋势跟踪',
    status: 'PUBLISHED',
    exchange: 'OKX',
    symbol: 'ETH/USDT',
    ownerId: 42,
    createdAt: '2026-07-04T08:00:00Z',
    updatedAt: '2026-07-05T09:00:00Z',
  },
]

export const strategyHandlers = [
  http.get('/api/v1/strategies', () => {
    return HttpResponse.json({ code: 0, message: 'ok', data: STRATEGIES })
  }),

  http.get('/api/v1/strategies/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const s = STRATEGIES.find((x) => x.id === id)
    if (!s) {
      return HttpResponse.json(
        { code: 7001, message: '策略不存在', data: null },
        { status: 404 },
      )
    }
    return HttpResponse.json({ code: 0, message: 'ok', data: s })
  }),

  http.post('/api/v1/strategies', async ({ request }) => {
    const body = (await request.json()) as Partial<MockStrategy>
    const created: MockStrategy = {
      id: 3,
      name: body.name ?? '新策略',
      description: body.description ?? '',
      status: 'DRAFT',
      exchange: body.exchange ?? 'BINANCE',
      symbol: body.symbol ?? 'BTC/USDT',
      ownerId: 42,
      createdAt: '2026-07-05T12:00:00Z',
      updatedAt: '2026-07-05T12:00:00Z',
    }
    return HttpResponse.json({ code: 0, message: 'ok', data: created }, { status: 201 })
  }),

  http.put('/api/v1/strategies/:id', async ({ params, request }) => {
    const id = parseInt(params.id as string, 10)
    const s = STRATEGIES.find((x) => x.id === id)
    if (!s) {
      return HttpResponse.json(
        { code: 7001, message: '策略不存在', data: null },
        { status: 404 },
      )
    }
    const body = (await request.json()) as Partial<MockStrategy>
    Object.assign(s, body, { updatedAt: '2026-07-05T13:00:00Z' })
    return HttpResponse.json({ code: 0, message: 'ok', data: s })
  }),

  http.delete('/api/v1/strategies/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const idx = STRATEGIES.findIndex((x) => x.id === id)
    if (idx < 0) {
      return HttpResponse.json(
        { code: 7001, message: '策略不存在', data: null },
        { status: 404 },
      )
    }
    return HttpResponse.json({ code: 0, message: 'ok', data: null })
  }),
]
