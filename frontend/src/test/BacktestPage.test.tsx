import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BacktestPage } from '../pages/BacktestPage'

const { mockList, mockDetail } = vi.hoisted(() => ({
  mockList: vi.fn(() => ({
    data: [
      {
        id: 2201, strategyId: 10, strategyCodeId: 100, status: 'COMPLETED',
        symbol: 'BTC/USDT', exchange: 'OKX', intervalValue: '1h',
        startTime: '2026-04-01T00:00:00Z', endTime: '2026-06-30T00:00:00Z',
        parameters: '{}', result: '', reportId: 1, errorMessage: '',
        processedBars: 0, totalBars: 0, totalReturn: 0.1532, strategyName: 'BTC Trend',
        createdAt: '2026-07-01T08:00:00Z', updatedAt: '2026-07-01T08:00:00Z',
      },
      {
        id: 2203, strategyId: 12, strategyCodeId: 102, status: 'RUNNING',
        symbol: 'SOL/USDT', exchange: 'OKX', intervalValue: '5m',
        startTime: '2026-04-01T00:00:00Z', endTime: '2026-06-30T00:00:00Z',
        parameters: '{}', result: '', reportId: 0, errorMessage: '',
        processedBars: 4400, totalBars: 8760, totalReturn: 0, strategyName: 'SOL 做市',
        createdAt: '2026-07-11T12:00:00Z', updatedAt: '2026-07-11T12:00:01Z',
      },
    ],
    isLoading: false,
    error: null,
  })),
  mockDetail: vi.fn(() => ({ data: undefined, isLoading: true, error: null })),
}))
vi.mock('@/hooks/useBacktest', () => ({
  useBacktestList: mockList,
  useReportDetail: mockDetail,
}))

describe('BacktestPage', () => {
  it('renders Header 新建回测 + rail COMPLETED 收益率 + RUNNING 进度', () => {
    render(
      <MemoryRouter>
        <BacktestPage />
      </MemoryRouter>,
    )
    expect(screen.getByText('回测')).toBeInTheDocument() // h1
    expect(screen.getByText('新建回测')).toBeInTheDocument() // Header 按钮
    expect(screen.getByText('BTC Trend')).toBeInTheDocument()
    expect(screen.getByText(/15\.32%/)).toBeInTheDocument() // COMPLETED 收益率
    expect(screen.getByText('SOL 做市')).toBeInTheDocument()
    expect(screen.getByText('运行中')).toBeInTheDocument() // RUNNING badge
  })
})
