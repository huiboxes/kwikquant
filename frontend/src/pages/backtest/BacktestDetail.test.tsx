import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { BacktestDetail } from './BacktestDetail'
import type { BacktestTaskDto } from '@/api/backtest'

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
        <BacktestDetail reportId={reportId} tasks={tasks} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BacktestDetail 头部', () => {
  it('显 回测报告 标题 + 策略名·符号·周期·区间 身份行', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('回测报告')).toBeInTheDocument())
    // 身份行:策略名 · 符号 · 周期 · 区间起 → 区间止
    expect(
      screen.getByText(/BTC Trend Rider v1\.3\.2 · BTC\/USDT · 1h · 2026-04-01 → 2026-06-30/),
    ).toBeInTheDocument()
  })

  it('导出 PNG/CSV 按钮在头部(不在曲线卡)', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText('回测报告')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /PNG/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /CSV/ })).toBeInTheDocument()
    // 头部容器内的按钮数 = 2(导出 PNG + 导出 CSV)
    const header = screen.getByText('回测报告').closest('div.flex.items-center.justify-between')
    const buttons = header?.querySelectorAll('button')
    expect(buttons?.length).toBe(2)
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
    // cell = MetricCell 外层(bg-surface-card-2),非 label 自己的 text-caption div
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

  it('交易明细方向 uppercase + 盈亏 + 前缀语义色 + 行 border', async () => {
    renderDetail(1, [task])
    await waitFor(() => expect(screen.getByText(/交易明细/)).toBeInTheDocument())
    // 方向 buy/sell(side 小写,CSS uppercase 渲染大写,DOM textContent 仍 'buy'/'sell')
    const buys = screen.getAllByText('buy')
    expect(buys.length).toBeGreaterThan(0)
    expect(buys[0].className).toContain('uppercase')
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
    expect(screen.getByText('1 order(s) placed on final bar were not executed')).toBeInTheDocument()
  })

  it('FAILED task 显回测失败态 + errorMessage + 重试 CTA,不显回测不存在', async () => {
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
