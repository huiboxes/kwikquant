import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * strategy MSW handlers(list + stop + pause + start;其他端点留 StrategyPage 任务)。
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

// 代码版本 mock(每策略一组,按 versionNumber 倒序;list 无 sourceCode,detail 有)
type StrategyCodeDto = components['schemas']['StrategyCodeDto']
type StrategyCodeDetailDto = components['schemas']['StrategyCodeDetailDto']
type CreateCodeRequest = components['schemas']['CreateCodeRequest']

const SOURCE_CODE = `import talib

def on_bar(ctx, bar, fast, slow, atr):
    # 双均线突破 + ATR 止损
    if fast[0] > slow[0] and fast[1] <= slow[1]:
        ctx.order(side='BUY', type='MARKET', qty=ctx.risk_qty(0.02, atr[0]),
                  stop=bar.close - atr[0] * 1.5,
                  take_profit=bar.close + atr[0] * 3)
`

const CODES: Record<number, StrategyCodeDto[]> = {
  1: [
    { id: 11, strategyId: 1, versionNumber: 3, status: 'DRAFT', language: 'python', changelog: '加入 ADX 过滤 · 放宽止损', createdAt: '2026-07-12T14:00:00Z', updatedAt: '2026-07-12T14:00:00Z' },
    { id: 12, strategyId: 1, versionNumber: 2, status: 'PUBLISHED', language: 'python', changelog: '修复 qty 计算精度问题', createdAt: '2026-07-09T10:00:00Z', updatedAt: '2026-07-09T10:00:00Z' },
    { id: 13, strategyId: 1, versionNumber: 1, status: 'ARCHIVED', language: 'python', changelog: '初版双均线突破', createdAt: '2026-07-01T08:00:00Z', updatedAt: '2026-07-01T08:00:00Z' },
  ],
  2: [
    { id: 21, strategyId: 2, versionNumber: 2, status: 'PUBLISHED', language: 'python', changelog: 'Z-score 阈值调优', createdAt: '2026-07-08T09:00:00Z', updatedAt: '2026-07-08T09:00:00Z' },
    { id: 22, strategyId: 2, versionNumber: 1, status: 'ARCHIVED', language: 'python', changelog: '初版布林带反转', createdAt: '2026-07-02T08:00:00Z', updatedAt: '2026-07-02T08:00:00Z' },
  ],
  3: [
    { id: 31, strategyId: 3, versionNumber: 1, status: 'PUBLISHED', language: 'python', changelog: '网格挂单初版', createdAt: '2026-07-03T08:00:00Z', updatedAt: '2026-07-03T08:00:00Z' },
  ],
  4: [
    { id: 41, strategyId: 4, versionNumber: 1, status: 'PUBLISHED', language: 'python', changelog: '网格挂单初版', createdAt: '2026-07-03T08:00:00Z', updatedAt: '2026-07-03T08:00:00Z' },
  ],
  5: [], // DRAFT 策略无代码版本(未发布)
}

let nextCodeId = 100
let nextVersionNumber = 4

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

  // POST /api/v1/strategies/{id}/pause → 暂停(RUNNING→PAUSED)
  http.post('/api/v1/strategies/:id/pause', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const s = STRATEGIES.find((x) => x.id === id)
    if (!s) {
      return HttpResponse.json(envelope(null, 7004, '策略不存在'), { status: 404 })
    }
    // 仅 RUNNING 可转移 PAUSED;其他 → 409(7002)
    if (s.status !== 'RUNNING') {
      return HttpResponse.json(envelope(null, 7002, `状态 ${s.status} 不可转移到 PAUSED`), {
        status: 409,
      })
    }
    s.status = 'PAUSED'
    s.updatedAt = new Date().toISOString()
    return HttpResponse.json(envelope(s))
  }),

  // POST /api/v1/strategies/{id}/start → 启动(READY→RUNNING 契约;前端也用于 PAUSED→RUNNING resume TD-033)
  http.post('/api/v1/strategies/:id/start', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const s = STRATEGIES.find((x) => x.id === id)
    if (!s) {
      return HttpResponse.json(envelope(null, 7004, '策略不存在'), { status: 404 })
    }
    // mock 对齐契约(READY)+ 既有用法(PAUSED resume):READY|PAUSED 可转移 RUNNING
    if (s.status !== 'PAUSED' && s.status !== 'READY') {
      return HttpResponse.json(envelope(null, 7002, `状态 ${s.status} 不可转移到 RUNNING`), {
        status: 409,
      })
    }
    s.status = 'RUNNING'
    s.updatedAt = new Date().toISOString()
    return HttpResponse.json(envelope(s))
  }),

  // ─── StrategyPage 新增端点 ───

  // GET /api/v1/strategies/{id} → 策略详情
  http.get('/api/v1/strategies/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const s = STRATEGIES.find((x) => x.id === id)
    if (!s) {
      return HttpResponse.json(envelope(null, 7001, '策略不存在'), { status: 404 })
    }
    return HttpResponse.json(envelope(s))
  }),

  // GET /api/v1/strategies/{strategyId}/codes → 代码版本列表(按 versionNumber 倒序,无 sourceCode)
  http.get('/api/v1/strategies/:strategyId/codes', ({ params }) => {
    const strategyId = parseInt(params.strategyId as string, 10)
    const codes = (CODES[strategyId] ?? []).slice().sort((a, b) => b.versionNumber - a.versionNumber)
    return HttpResponse.json(envelope(codes))
  }),

  // GET /api/v1/strategies/{strategyId}/codes/{codeId} → 代码版本详情(含 sourceCode)
  http.get('/api/v1/strategies/:strategyId/codes/:codeId', ({ params }) => {
    const strategyId = parseInt(params.strategyId as string, 10)
    const codeId = parseInt(params.codeId as string, 10)
    const list = CODES[strategyId] ?? []
    const c = list.find((x) => x.id === codeId)
    if (!c) {
      return HttpResponse.json(envelope(null, 7004, '代码版本不存在'), { status: 404 })
    }
    const detail: StrategyCodeDetailDto = {
      ...c,
      sourceCode: SOURCE_CODE,
    }
    return HttpResponse.json(envelope(detail))
  }),

  // POST /api/v1/strategies/{strategyId}/codes → 新建代码草稿(DRAFT)
  http.post('/api/v1/strategies/:strategyId/codes', async ({ request, params }) => {
    const strategyId = parseInt(params.strategyId as string, 10)
    const body = (await request.json()) as CreateCodeRequest
    const list = CODES[strategyId] ?? (CODES[strategyId] = [])
    if (list.some((c) => c.status === 'DRAFT')) {
      return HttpResponse.json(envelope(null, 7005, '已有未发布草稿'), { status: 409 })
    }
    const code: StrategyCodeDto = {
      id: nextCodeId++,
      strategyId,
      versionNumber: nextVersionNumber++,
      status: 'DRAFT',
      language: 'python',
      changelog: body.changelog ?? '',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    list.unshift(code)
    return HttpResponse.json(envelope(code))
  }),

  // PUT /api/v1/strategies/{strategyId}/codes/{codeId} → 更新草稿(仅 DRAFT)
  http.put('/api/v1/strategies/:strategyId/codes/:codeId', async ({ request, params }) => {
    const strategyId = parseInt(params.strategyId as string, 10)
    const codeId = parseInt(params.codeId as string, 10)
    const body = (await request.json()) as CreateCodeRequest
    const list = CODES[strategyId] ?? []
    const c = list.find((x) => x.id === codeId)
    if (!c) {
      return HttpResponse.json(envelope(null, 7004, '代码版本不存在'), { status: 404 })
    }
    if (c.status !== 'DRAFT') {
      return HttpResponse.json(envelope(null, 7005, '非草稿不可改'), { status: 409 })
    }
    c.changelog = body.changelog ?? c.changelog
    c.updatedAt = new Date().toISOString()
    const detail: StrategyCodeDetailDto = { ...c, sourceCode: body.sourceCode }
    return HttpResponse.json(envelope(detail))
  }),

  // POST /api/v1/strategies/{strategyId}/codes/{codeId}/publish → 发布(DRAFT→PUBLISHED 冻结)
  http.post('/api/v1/strategies/:strategyId/codes/:codeId/publish', ({ params }) => {
    const strategyId = parseInt(params.strategyId as string, 10)
    const codeId = parseInt(params.codeId as string, 10)
    const list = CODES[strategyId] ?? []
    const c = list.find((x) => x.id === codeId)
    if (!c) {
      return HttpResponse.json(envelope(null, 7004, '代码版本不存在'), { status: 404 })
    }
    if (c.status !== 'DRAFT') {
      return HttpResponse.json(envelope(null, 7005, '非草稿不可发布'), { status: 409 })
    }
    c.status = 'PUBLISHED'
    c.updatedAt = new Date().toISOString()
    // 之前的 PUBLISHED → ARCHIVED(只保留一个 PUBLISHED)
    for (const other of list) {
      if (other !== c && other.status === 'PUBLISHED') {
        other.status = 'ARCHIVED'
      }
    }
    return HttpResponse.json(envelope(c))
  }),

  // POST /api/v1/strategies/{id}/ready → 标记就绪(DRAFT→READY,需有发布代码)
  http.post('/api/v1/strategies/:id/ready', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const s = STRATEGIES.find((x) => x.id === id)
    if (!s) {
      return HttpResponse.json(envelope(null, 7001, '策略不存在'), { status: 404 })
    }
    if (s.status !== 'DRAFT') {
      return HttpResponse.json(envelope(null, 7002, `状态 ${s.status} 不可转移到 READY`), {
        status: 409,
      })
    }
    const hasPublished = (CODES[id] ?? []).some((c) => c.status === 'PUBLISHED')
    if (!hasPublished) {
      return HttpResponse.json(envelope(null, 7006, '无发布代码'), { status: 409 })
    }
    s.status = 'READY'
    s.updatedAt = new Date().toISOString()
    return HttpResponse.json(envelope(s))
  }),

  // POST /api/v1/ai/chat → AI 对话 SSE 流式(mock:分 4 段发送,Flux<ServerSentEvent>,不套 envelope)
  // 注:此 handler 放 strategy.ts 因 StrategyPage 是唯一消费方(避免新建 handlers/ai.ts)。
  http.post('/api/v1/ai/chat', () => {
    const encoder = new TextEncoder()
    const response =
      '好的,我看了你的策略上下文。发现两点可以优化:1. 入场过滤太弱,建议加 ADX>25 趋势过滤。2. 止损偏紧,ATR×1.5 在高波动品种易被扫损,考虑放到 ATR×2.5。'
    const parts = response.match(/.{1,12}/g) ?? [response]
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        let i = 0
        const timer = setInterval(() => {
          if (i >= parts.length) {
            controller.close()
            clearInterval(timer)
            return
          }
          controller.enqueue(encoder.encode(`data: ${parts[i++]}\n\n`))
        }, 20)
      },
    })
    return new HttpResponse(stream, {
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      },
    })
  }),
]
