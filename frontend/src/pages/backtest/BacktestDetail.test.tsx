import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { BacktestDetail } from './BacktestDetail'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'
import type { BacktestTaskDto } from '@/api/backtest'

/** 路由探针:BacktestDetail 内 navigate 后断言目标 URL(含 query)。 */
function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

// msw server(setup.ts listen)+ handlers/backtest.ts 已 mock GET /api/v1/reports/:id
// report id=1 → makeDetail(REPORTS[0]):symbol='BTC/USDT' timeframe='1h'
//   periodStart='2026-04-01T00:00:00Z' periodEnd='2026-06-30T00:00:00Z'

const task: BacktestTaskDto = {
  id: 2201,
  strategyId: 10,
  strategyCodeId: 100,
  status: 'COMPLETED',
  symbol: 'BTC/USDT',
  exchange: 'OKX',
  intervalValue: '1h',
  startTime: '2026-04-01T00:00:00Z',
  endTime: '2026-06-30T00:00:00Z',
  parameters: '{}',
  result: '',
  reportId: 1,
  errorMessage: '',
  processedBars: 0,
  totalBars: 0,
  totalReturn: 0.1532,
  strategyName: 'BTC Trend Rider v1.3.2',
  createdAt: '2026-07-01T08:00:00Z',
  updatedAt: '2026-07-01T08:00:00Z',
} as unknown as BacktestTaskDto

function renderDetail(reportId: number | null, tasks: BacktestTaskDto[]) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <BacktestDetail
          reportId={reportId}
          selectedTaskId={tasks.find((task) => task.reportId === reportId)?.id ?? null}
          tasks={tasks}
        />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BacktestDetail 头部', () => {
  it('显 回测报告 标题 + 策略名·符号·周期·区间 身份行', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('回测报告')).toBeInTheDocument())
    // 身份行：策略名 · 符号 · 周期 · 区间起 → 区间止
    expect(
      screen.getByText(/BTC Trend Rider v1\.3\.2 · BTC\/USDT · 1h · 2026-04-01 → 2026-06-30/),
    ).toBeInTheDocument()
  })

  it('导出 JSON/PNG/CSV 按钮在头部(不在曲线卡)', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('回测报告')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /JSON/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /PNG/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /CSV/ })).toBeInTheDocument()
    // 头部容器内的按钮数 = 4(AI 解读 + 导出 JSON + 导出 PNG + 导出 CSV)
    const header = screen.getByText('回测报告').closest('div.flex.items-center.justify-between')
    const buttons = header?.querySelectorAll('button')
    expect(buttons?.length).toBe(4)
  })

  it('AI 解读按钮 → 深链 /strategy?strategyId&reportId&ai=1(回测解读入口)', async () => {
    const user = userEvent.setup()
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('回测报告')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /AI 解读/ }))
    // task.strategyId=10 + reportId=1 + ai=1 门控参数齐全
    expect(screen.getByTestId('location')).toHaveTextContent('/strategy?strategyId=10&reportId=1&ai=1')
  })

  it('无关联任务(纯 reportId 直达)→ 不渲染 AI 解读(解读会话必须归属策略)', async () => {
    renderDetail(1, [])
    await waitFor(() => expect(screen.getByText('回测报告')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /AI 解读/ })).not.toBeInTheDocument()
  })

  it('曲线卡只留 权益曲线 标题(导出按钮已迁出)', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('权益曲线')).toBeInTheDocument())
    // 曲线卡(权益曲线标题的父容器)内无按钮
    const curveCard = screen.getByText('权益曲线').closest('div.rounded-xl')
    const buttons = curveCard?.querySelectorAll('button')
    expect(buttons?.length).toBe(0)
  })

  it('MetricGrid 4 列 + cell bg-surface-card-2 + label 全称', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('总收益率')).toBeInTheDocument())
    const grid = screen.getByText('总收益率').closest('.grid')!
    expect(grid.className).toContain('grid-cols-4')
    // cell = MetricCell 外层(bg-surface-card-2)，非 label 自己的 text-caption div
    const cell = screen.getByText('总收益率').closest('div.rounded-lg')!
    expect(cell.className).toContain('bg-surface-card-2')
    // label 全称
    expect(screen.getByText('夏普比率')).toBeInTheDocument()
    expect(screen.getByText('最大回撤')).toBeInTheDocument()
    expect(screen.getByText('平均持仓时长')).toBeInTheDocument()
    // 7 个指标(label)
    expect(screen.getByText('胜率')).toBeInTheDocument()
    expect(screen.getByText('盈亏比')).toBeInTheDocument()
    expect(screen.getByText('交易数')).toBeInTheDocument()
  })

  it('曲线 4 角标(区间起止 + 当前/初始权益)', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('权益曲线')).toBeInTheDocument())
    // 区间起止(report id=1: periodStart='2026-04-01' periodEnd='2026-06-30')
    expect(screen.getByText('2026-04-01')).toBeInTheDocument()
    expect(screen.getByText('2026-06-30')).toBeInTheDocument()
    // 初始权益角标(含"初始"文字)
    expect(screen.getByText(/初始/)).toBeInTheDocument()
  })

  it('交易明细方向中文 + 盈亏 + 前缀语义色 + 行 border', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText(/交易明细/)).toBeInTheDocument())
    const buys = screen.getAllByText('买入')
    expect(buys.length).toBeGreaterThan(0)
    expect(buys[0].className).toContain('text-up')
    // 盈亏正值带 + 前缀(handlers TRADES sell 单 realizedPnl=109.2 → "+109.20";buy realizedPnl=0 → "+0.00")
    const pnl = screen.getAllByText(/^\+\d/)
    expect(pnl.length).toBeGreaterThan(0)
    // 行 border(soft/30)
    const rows = screen.getAllByRole('row')
    expect(rows.length).toBeGreaterThan(1) // 表头 + 数据行
  })

  it('展示可复现快照和可信度 warning', async () => {
    renderDetail(1, [task])
    expect(await screen.findByText('可复现快照')).toBeInTheDocument()
    expect(screen.getByText('可信度提示')).toBeInTheDocument()
    expect(screen.getByText('sha256:strategy-abc')).toBeInTheDocument()
    expect(screen.getByText('NEXT_BAR')).toBeInTheDocument()
    expect(
      screen.getByText('末尾 bar 提交的 1 笔订单未参与撮合（回测区间已结束，NEXT_BAR 无下一根）'),
    ).toBeInTheDocument()
  })

  it('FAILED task 显回测失败态 + errorMessage + 重试 CTA，不显回测不存在', async () => {
    const failedTask = {
      ...task,
      id: 2205,
      reportId: 2205,
      status: 'FAILED',
      errorMessage: 'worker timeout',
    } as unknown as BacktestTaskDto
    renderDetail(2205, [failedTask])
    expect(await screen.findByText('回测失败')).toBeInTheDocument()
    expect(screen.getByText('worker timeout')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新发起回测' })).toBeInTheDocument()
    expect(screen.queryByText('回测不存在')).not.toBeInTheDocument()
  })
})

// ---- PERP 报告展示(perp-backtest-spec §8.1/§8.2 前端消费) ----

const perpDetail = {
  id: 7,
  name: 'PERP 趋势',
  symbol: 'BTC/USDT:USDT',
  symbols: [],
  marketType: 'PERP',
  liquidationModel: 'BAR_EXTREME_APPROX',
  timeframe: '1h',
  periodStart: '2026-04-01T00:00:00Z',
  periodEnd: '2026-06-30T00:00:00Z',
  params: JSON.stringify({
    initial_capital: '10000',
    _kwikquant: {
      strategyCodeHash: 'sha256:perp-abc',
      data: {
        requestedStart: '2026-04-01T00:00:00Z',
        requestedEnd: '2026-06-30T00:00:00Z',
        actualStart: '2026-04-01T00:00:00Z',
        actualEnd: '2026-06-30T00:00:00Z',
        bars: 2160,
        version: 'sha256:kline-def',
        fundingVersion: 'sha256:funding-v',
        fundingPeriods: 273,
      },
      execution: { engineVersion: 'backtest-event-loop-v5', orderFillTiming: 'NEXT_BAR' },
      // 资金费结算统计不再进 warnings(spec §8:纯信息项与快照 fundingPeriods 同源);
      // 夹具用真实会出现的跨所代理标注
      warnings: ['资金费跨所代理：序列含 12 期 PROXY_BINANCE 代理费率（存在跨所基差；持仓跨越这些期次时按代理值结算，成本与本所真值有偏差）'],
    },
  }),
  metrics: {
    totalReturn: -0.05,
    sharpeRatio: null,
    maxDrawdown: 0.08,
    winRate: 0,
    profitFactor: 0,
    totalTrades: 1,
    avgTradeDurationSeconds: 14400,
  },
  trades: [
    { id: 1, time: '2026-05-01T08:00:00Z', symbol: null, side: 'buy', positionEffect: 'OPEN_LONG', liquidation: false, price: 42000, amount: 1, fee: 8.4, realizedPnl: -8.4, equity: null },
    { id: 2, time: '2026-05-01T12:00:00Z', symbol: null, side: 'sell', positionEffect: 'CLOSE_LONG', liquidation: true, price: 38000, amount: 1, fee: 7.6, realizedPnl: -4007.6, equity: null },
  ],
  equityCurve: [
    { time: '2026-04-01T00:00:00Z', equity: 10000, marginUsed: 420, fundingCum: 0 },
    { time: '2026-06-30T00:00:00Z', equity: 9500, marginUsed: 0, fundingCum: -12.5 },
  ],
  positions: [],
  source: 'PLATFORM',
  createdAt: '2026-07-01T08:00:00Z',
  updatedAt: '2026-07-01T08:00:00Z',
}

const perpTask = {
  id: 2207,
  strategyId: 10,
  strategyCodeId: 100,
  status: 'COMPLETED',
  symbol: 'BTC/USDT:USDT',
  exchange: 'OKX',
  intervalValue: '1h',
  reportId: 7,
  marketType: 'PERP',
  strategyName: 'PERP 趋势',
} as unknown as BacktestTaskDto

function usePerpReportMock() {
  server.use(
    http.get('/api/v1/reports/7', () => HttpResponse.json(envelope(perpDetail))),
  )
}

describe('BacktestDetail PERP 报告', () => {
  it('头部合约 badge + 强平近似声明 banner', async () => {
    usePerpReportMock()
    renderDetail(7, [perpTask])
    expect(await screen.findByText('回测报告')).toBeInTheDocument()
    expect(screen.getByText('合约')).toBeInTheDocument()
    // 近似声明:模型中文名 + 保守偏差 + 毛配对口径提示
    expect(screen.getByText(/bar 极值近似/)).toBeInTheDocument()
    expect(screen.getByText(/保守偏差/)).toBeInTheDocument()
  })

  it('SPOT 报告无合约 badge 与近似声明', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('回测报告')).toBeInTheDocument())
    expect(screen.queryByText('合约')).not.toBeInTheDocument()
    expect(screen.queryByText(/近似声明/)).not.toBeInTheDocument()
  })

  it('MetricGrid 追加累计资金费 cell(末点 fundingCum,负值)', async () => {
    usePerpReportMock()
    renderDetail(7, [perpTask])
    expect(await screen.findByText('累计资金费')).toBeInTheDocument()
    expect(screen.getByText('-12.50')).toBeInTheDocument()
  })

  it('交易明细显四向中文 + 强平标记,权益列显 —', async () => {
    usePerpReportMock()
    renderDetail(7, [perpTask])
    expect(await screen.findByText('开多')).toBeInTheDocument()
    expect(screen.getByText('平多')).toBeInTheDocument()
    expect(screen.getByText('强平')).toBeInTheDocument()
    // 表头切换为持仓意图
    expect(screen.getByText('持仓意图')).toBeInTheDocument()
    expect(screen.queryByText('买入')).not.toBeInTheDocument()
    // PERP 逐笔累计权益恒 null → —(两行)
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2)
  })

  it('可复现快照带资金费数据行 + funding warnings 透出', async () => {
    usePerpReportMock()
    renderDetail(7, [perpTask])
    expect(await screen.findByText('资金费数据')).toBeInTheDocument()
    expect(screen.getByText('sha256:funding-v · 273 期')).toBeInTheDocument()
    // warnings 走既有 params._kwikquant 链路(可信度提示)
    expect(
      screen.getByText('资金费跨所代理：序列含 12 期 PROXY_BINANCE 代理费率（存在跨所基差；持仓跨越这些期次时按代理值结算，成本与本所真值有偏差）'),
    ).toBeInTheDocument()
  })
})

describe('BacktestDetail 组合报告头部', () => {
  it('身份行显汇总标签而非逗号长串,完整清单进 title(与 BacktestRail 同口径)', async () => {
    const symbols = ['BTC/USDT', 'ETH/USDT', 'SOL/USDT']
    server.use(
      http.get('/api/v1/reports/9', () =>
        HttpResponse.json(
          envelope({ ...perpDetail, id: 9, symbol: symbols.join(','), symbols }),
        ),
      ),
    )
    const portfolioTask = {
      ...perpTask,
      id: 2209,
      reportId: 9,
      symbol: symbols.join(','),
      symbols,
    } as unknown as BacktestTaskDto
    renderDetail(9, [portfolioTask])
    expect(await screen.findByText('回测报告')).toBeInTheDocument()
    expect(screen.getByText(/组合·3 标的/)).toBeInTheDocument()
    expect(screen.getByTitle(symbols.join(', '))).toBeInTheDocument()
  })
})
