import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BacktestPage } from '../pages/BacktestPage'

const { mockList, mockDetail, mockCompare, mockImport } = vi.hoisted(() => ({
  mockCompare: vi.fn(() => ({ mutate: vi.fn(), data: undefined, isPending: false, error: null })),
  mockImport: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
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
  useCompareReports: mockCompare,
  useImportReport: mockImport,
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

  it('trio:导入报告按钮 + 对比按钮(<2 选禁用) + COMPLETED 卡片对比勾选框', () => {
    render(
      <MemoryRouter>
        <BacktestPage />
      </MemoryRouter>,
    )
    expect(screen.getByText('导入报告')).toBeInTheDocument()
    const compareBtn = screen.getByRole('button', { name: /对比/ })
    expect(compareBtn).toBeDisabled() // 未勾选 → 禁用
    // COMPLETED 卡片有对比勾选框(RUNNING 卡片无)
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(1)
    // 勾选唯一 COMPLETED → 按钮文案带 (1),仍 <2 禁用
    fireEvent.click(checkboxes[0])
    expect(screen.getByRole('button', { name: /对比 \(1\)/ })).toBeDisabled()
  })
})
