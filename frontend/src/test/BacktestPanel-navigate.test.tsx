import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { BacktestPanel } from '../pages/strategy/BacktestPanel'

const { mockTasks, mockDetail, mockNavigate } = vi.hoisted(() => ({
  mockTasks: vi.fn(() => ({
    data: [{ id: 1, strategyId: 10, status: 'COMPLETED', reportId: 123 }],
    isLoading: false,
    error: null,
  })),
  mockDetail: vi.fn(() => ({
    data: {
      metrics: {
        totalReturn: 0.1,
        sharpeRatio: 1.5,
        maxDrawdown: 0.05,
        winRate: 0.6,
        profitFactor: 1.8,
        totalTrades: 10,
        avgTradeDurationSeconds: 3600,
      },
      equityCurve: [],
      trades: [],
    },
    isLoading: false,
    error: null,
  })),
  mockNavigate: vi.fn(),
}))

vi.mock('@/hooks/useBacktest', () => ({
  useBacktestTasksByStrategy: mockTasks,
  useReportDetail: mockDetail,
}))
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return { ...actual, useNavigate: () => mockNavigate }
})

describe('BacktestPanel 查看详情死链修复', () => {
  it('Button 点击跳 /backtest?reportId=123', async () => {
    render(
      <MemoryRouter>
        <BacktestPanel strategyId={10} />
      </MemoryRouter>,
    )
    // 详情态显"查看详情"Button(ExternalLink button title 也是"查看详情"但 getByText match 文本)
    await userEvent.click(screen.getByText('查看详情'))
    expect(mockNavigate).toHaveBeenCalledWith('/backtest?reportId=123')
  })
})
