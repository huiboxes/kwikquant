import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * backtest MSW handlers(reports + tasks;BacktestPage 用)。
 * mock 数据照原型 AppContext backtests 适配 BacktestReportDto / BacktestReportDetailDto / BacktestTaskDto。
 *
 * honest:list rail 只展 reports(COMPLETED);submit→PENDING→轮询 RUNNING→COMPLETED+reportId
 * (照 behavior-contract §3)。trades.side 契约 "buy"|"sell"(小写,api-gen line 3449),
 * page 层 upper() 显示 BUY/SELL。
 *
 * 金额说明:equityCurve equity 是 mock 数据生成(number 派生,非金额运算;page 层 toDecimal
 * 转换展示)。parseInt 替 Number(金额红线)。Math.round 二元算术 lint 不拦(TD-014 gap),
 * 此处是 mock 数据四舍五入非金额运算。
 */
type BacktestReportDto = components['schemas']['BacktestReportDto']
type BacktestReportDetailDto = components['schemas']['BacktestReportDetailDto']
type BacktestTaskDto = components['schemas']['BacktestTaskDto']
type TradeRecordDto = components['schemas']['TradeRecordDto']
type EquityPointDto = components['schemas']['EquityPointDto']
type MetricsDto = components['schemas']['MetricsDto']

// 6 报告(照原型 bt-2201..2206 风格,id 用 1-6 number;totalReturn/maxDrawdown/winRate 是小数)
const REPORTS: BacktestReportDto[] = [
  {
    id: 1,
    name: 'BTC Trend Rider v1.3.2',
    symbol: 'BTC/USDT',
    timeframe: '1h',
    periodStart: '2026-04-01T00:00:00Z',
    periodEnd: '2026-06-30T00:00:00Z',
    totalReturn: 0.1532,
    sharpeRatio: 1.85,
    maxDrawdown: -0.0842,
    winRate: 0.62,
    profitFactor: 2.1,
    totalTrades: 128,
    source: 'BACKTEST',
    createdAt: '2026-07-01T08:00:00Z',
  },
  {
    id: 2,
    name: 'ETH Mean Reversion v0.4.1',
    symbol: 'ETH/USDT',
    timeframe: '15m',
    periodStart: '2026-04-01T00:00:00Z',
    periodEnd: '2026-06-30T00:00:00Z',
    totalReturn: 0.0871,
    sharpeRatio: 1.42,
    maxDrawdown: -0.112,
    winRate: 0.55,
    profitFactor: 1.8,
    totalTrades: 96,
    source: 'BACKTEST',
    createdAt: '2026-07-02T08:00:00Z',
  },
  {
    id: 3,
    name: 'SOL 做市 v0.2.0',
    symbol: 'SOL/USDT',
    timeframe: '5m',
    periodStart: '2026-04-01T00:00:00Z',
    periodEnd: '2026-06-30T00:00:00Z',
    totalReturn: -0.0321,
    sharpeRatio: 0.85,
    maxDrawdown: -0.156,
    winRate: 0.48,
    profitFactor: 0.9,
    totalTrades: 64,
    source: 'BACKTEST',
    createdAt: '2026-07-03T08:00:00Z',
  },
  {
    id: 4,
    name: 'Grid Scalper v2.1.0',
    symbol: 'SOL/USDT',
    timeframe: '1m',
    periodStart: '2026-04-01T00:00:00Z',
    periodEnd: '2026-06-30T00:00:00Z',
    totalReturn: 0.1241,
    sharpeRatio: 1.65,
    maxDrawdown: -0.072,
    winRate: 0.68,
    profitFactor: 2.4,
    totalTrades: 152,
    source: 'BACKTEST',
    createdAt: '2026-07-04T08:00:00Z',
  },
  {
    id: 5,
    name: 'Funding Arb v1.0.0',
    symbol: 'BTC/USDT',
    timeframe: '4h',
    periodStart: '2026-04-01T00:00:00Z',
    periodEnd: '2026-06-30T00:00:00Z',
    totalReturn: 0.0512,
    sharpeRatio: 1.12,
    maxDrawdown: -0.045,
    winRate: 0.71,
    profitFactor: 1.5,
    totalTrades: 88,
    source: 'BACKTEST',
    createdAt: '2026-07-05T08:00:00Z',
  },
  {
    id: 6,
    name: 'BTC Trend Rider v2.0',
    symbol: 'BTC/USDT',
    timeframe: '1h',
    periodStart: '2026-04-01T00:00:00Z',
    periodEnd: '2026-06-30T00:00:00Z',
    totalReturn: 0.2211,
    sharpeRatio: 2.05,
    maxDrawdown: -0.098,
    winRate: 0.65,
    profitFactor: 2.6,
    totalTrades: 144,
    source: 'BACKTEST',
    createdAt: '2026-07-06T08:00:00Z',
  },
]

// 权益曲线 30 点(2026-06-01 起,sin 趋势上扬,稳定无随机;mock 数据生成非金额运算)
function genEquityCurve(): EquityPointDto[] {
  return Array.from({ length: 30 }, (_, i) => ({
    time: new Date(Date.UTC(2026, 5, 1) + i * 86400000).toISOString(),
    equity: Math.round((100000 * (1 + 0.15 * (i / 29) + Math.sin(i * 0.5) * 0.02)) * 100) / 100,
  }))
}

// 交易明细 6 笔(照原型 BacktestPage.jsx line 53-58;side 契约小写,page 层 upper 显示)
// trades mock(TD-019):realizedPnl/equity 真实派生——buy 单 0(未平仓),sell 单算 (卖价-前买价)*amount;
// equity 累计盈亏递增(初始 100000)。契约标 number 但运行时可 null(首单/无配对),mock 用 0 不测 null。
const TRADES: TradeRecordDto[] = [
  { id: 1, time: '2026-06-18T14:02:00Z', side: 'buy', price: 60200, amount: 0.42, fee: 0.0052, realizedPnl: 0, equity: 100000 },
  { id: 2, time: '2026-06-15T09:14:00Z', side: 'sell', price: 62800, amount: 0.42, fee: 0.0052, realizedPnl: 109.2, equity: 100109.2 },
  { id: 3, time: '2026-06-12T22:38:00Z', side: 'buy', price: 58200, amount: 0.42, fee: 0.0052, realizedPnl: 0, equity: 100109.2 },
  { id: 4, time: '2026-06-09T11:02:00Z', side: 'sell', price: 60100, amount: 0.42, fee: 0.0052, realizedPnl: 79.8, equity: 100189 },
  { id: 5, time: '2026-06-05T16:48:00Z', side: 'buy', price: 55800, amount: 0.42, fee: 0.0052, realizedPnl: 0, equity: 100189 },
  { id: 6, time: '2026-06-02T08:22:00Z', side: 'sell', price: 57200, amount: 0.42, fee: 0.0052, realizedPnl: 58.8, equity: 100247.8 },
]

// 详情(metrics + trades + equityCurve;avgTradeDurationSeconds=22320=6h12m 照原型 bt.avgHold)
function makeDetail(report: BacktestReportDto): BacktestReportDetailDto {
  const metrics: MetricsDto = {
    totalReturn: report.totalReturn,
    sharpeRatio: report.sharpeRatio,
    maxDrawdown: report.maxDrawdown,
    winRate: report.winRate,
    profitFactor: report.profitFactor,
    totalTrades: report.totalTrades,
    avgTradeDurationSeconds: 22320,
  }
  return {
    id: report.id,
    name: report.name,
    symbol: report.symbol,
    timeframe: report.timeframe,
    periodStart: report.periodStart,
    periodEnd: report.periodEnd,
    params: '{"initial_capital":100000,"slippage":0.0005,"fee":0.0004}',
    metrics,
    trades: TRADES,
    equityCurve: genEquityCurve(),
    source: report.source,
    createdAt: report.createdAt,
    updatedAt: report.createdAt,
  }
}

// 任务轮询 stateful(behavior-contract §3:PENDING→RUNNING→COMPLETED)
let nextTaskId = 9001
const TASKS = new Map<number, BacktestTaskDto>()

// 推进任务状态(下次 GET 返推进后状态;COMPLETED 终态不变)
function advanceTask(task: BacktestTaskDto): void {
  if (task.status === 'PENDING') {
    task.status = 'RUNNING'
    task.updatedAt = '2026-07-11T12:00:01Z'
  } else if (task.status === 'RUNNING') {
    task.status = 'COMPLETED'
    task.reportId = 1 // 模拟完成入库 → 指向第一个报告(契约改动 B:reportId 回填)
    task.result = '{"realizedPnl":184.20,"tradeCount":128}'
    task.updatedAt = '2026-07-11T12:00:05Z'
  }
}

export const backtestHandlers = [
  // GET /api/v1/reports → 报告列表(分页;返回全部 6 条,page/pageSize 透传)
  http.get('/api/v1/reports', ({ request }) => {
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '1', 10)
    const pageSize = parseInt(url.searchParams.get('pageSize') ?? '20', 10)
    const start = (page - 1) * pageSize
    const content = REPORTS.slice(start, start + pageSize)
    return HttpResponse.json(
      envelope({
        content,
        page,
        pageSize,
        total: REPORTS.length,
        totalPages: Math.ceil(REPORTS.length / pageSize) || 1,
      }),
    )
  }),

  // GET /api/v1/reports/{id} → 报告详情(metrics + trades + equityCurve)
  http.get('/api/v1/reports/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const report = REPORTS.find((r) => r.id === id)
    if (!report) {
      return HttpResponse.json(envelope(null, 7100, '回测报告不存在'), { status: 404 })
    }
    return HttpResponse.json(envelope(makeDetail(report)))
  }),

  // POST /api/v1/reports/compare → 对比(reportIds 2-20;返 reports + ranking 按 totalReturn/sharpe/profitFactor 降序)
  http.post('/api/v1/reports/compare', async ({ request }) => {
    const body = (await request.json()) as { reportIds: number[] }
    const ids = body.reportIds ?? []
    const reports = ids
      .map((id) => REPORTS.find((r) => r.id === id))
      .filter(Boolean) as BacktestReportDto[]
    const rankBy = (key: keyof BacktestReportDto) =>
      [...reports]
        .sort((a, b) => (b[key] as number) - (a[key] as number))
        .map((r) => r.id)
    return HttpResponse.json(
      envelope({
        reports,
        ranking: {
          totalReturn: rankBy('totalReturn'),
          sharpeRatio: rankBy('sharpeRatio'),
          profitFactor: rankBy('profitFactor'),
        },
      }),
    )
  }),

  // POST /api/v1/reports/import → 导入外部报告(BacktestPage "导入"按钮接此;返 id=9999 IMPORT 报告)
  http.post('/api/v1/reports/import', async () => {
    const report: BacktestReportDto = {
      id: 9999,
      name: '导入的外部报告',
      symbol: 'BTC/USDT',
      timeframe: '1h',
      periodStart: '2026-05-01T00:00:00Z',
      periodEnd: '2026-06-30T00:00:00Z',
      totalReturn: 0.0975,
      sharpeRatio: 1.55,
      maxDrawdown: -0.061,
      winRate: 0.58,
      profitFactor: 1.9,
      totalTrades: 72,
      source: 'IMPORT',
      createdAt: '2026-07-11T08:00:00Z',
    }
    return HttpResponse.json(envelope(report))
  }),

  // POST /api/v1/backtests → 提交回测(异步返 PENDING task;策略不存在 404 7001,无发布代码 409 7006)
  http.post('/api/v1/backtests', async ({ request }) => {
    const body = (await request.json()) as components['schemas']['SubmitBacktestRequest']
    if (!body.strategyId) {
      return HttpResponse.json(envelope(null, 7001, '策略不存在'), { status: 404 })
    }
    const id = nextTaskId++
    const task: BacktestTaskDto = {
      id,
      strategyId: body.strategyId,
      strategyCodeId: 256,
      status: 'PENDING',
      symbol: body.symbol ?? 'BTC/USDT',
      exchange: body.exchange ?? 'BINANCE',
      intervalValue: body.intervalValue ?? '1h',
      startTime: body.startTime ?? '2026-06-01T00:00:00Z',
      endTime: body.endTime ?? '2026-06-30T00:00:00Z',
      parameters: body.parameters ?? '{}',
      result: '',
      reportId: 0,
      errorMessage: '',
      createdAt: '2026-07-11T12:00:00Z',
      updatedAt: '2026-07-11T12:00:00Z',
    }
    TASKS.set(id, task)
    return HttpResponse.json(envelope(task))
  }),

  // GET /api/v1/backtests/{id} → 轮询(每次 GET 返当前快照 + 推进内部状态;终态 COMPLETED/FAILED 不变)
  http.get('/api/v1/backtests/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const task = TASKS.get(id)
    if (!task) {
      return HttpResponse.json(envelope(null, 7100, '回测任务不存在'), { status: 404 })
    }
    const current = { ...task }
    advanceTask(task)
    return HttpResponse.json(envelope(current))
  }),
]
