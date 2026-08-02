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
})
