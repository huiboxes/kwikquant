import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { BacktestResultPanel } from './BacktestResultPanel'

const { mockTask, mockReport } = vi.hoisted(() => ({
  mockTask: vi.fn(),
  mockReport: vi.fn(),
}))

vi.mock('@/hooks/useBacktestTask', () => ({ useBacktestTask: mockTask }))
vi.mock('@/hooks/useBacktestReport', () => ({ useBacktestReport: mockReport }))
vi.mock('@/components/EquityChart', () => ({
  EquityChart: () => <div data-testid="equity" />,
}))
vi.mock('@/components/BacktestResultArea', () => ({
  BacktestResultArea: () => <div data-testid="area" />,
}))

const METRICS = {
  totalReturn: 0.15,
  sharpeRatio: 1.2,
  maxDrawdown: -0.1,
  winRate: 0.6,
  profitFactor: 1.5,
  totalTrades: 10,
  avgTradeDurationSeconds: 3600,
}

beforeEach(() => {
  mockTask.mockReturnValue({ data: null, isLoading: false, error: null })
  mockReport.mockReturnValue({ data: undefined, isLoading: false, error: null })
})

describe('BacktestResultPanel', () => {
  it('taskId=null 显空态(含 Run Live 引导)', () => {
    render(<BacktestResultPanel taskId={null} onRetry={vi.fn()} isRetrying={false} />)
    expect(screen.getByText(/Run Live/)).toBeInTheDocument()
  })

  it('COMPLETED 显 Complete 标签 + 查看详情按钮', () => {
    mockTask.mockReturnValue({
      data: { status: 'COMPLETED', reportId: 42, errorMessage: null },
      isLoading: false,
      error: null,
    })
    mockReport.mockReturnValue({
      data: { id: 42, name: 'r42', equityCurve: [], metrics: METRICS, trades: [] },
      isLoading: false,
      error: null,
    })
    render(<BacktestResultPanel taskId={1} onRetry={vi.fn()} isRetrying={false} />)
    expect(screen.getByText('Complete')).toBeInTheDocument()
    expect(screen.getByText('查看详情')).toBeInTheDocument()
  })

  it('FAILED 显回测失败 + errorMessage + 重试', () => {
    mockTask.mockReturnValue({
      data: { status: 'FAILED', reportId: null, errorMessage: 'timeout' },
      isLoading: false,
      error: null,
    })
    render(<BacktestResultPanel taskId={1} onRetry={vi.fn()} isRetrying={false} />)
    expect(screen.getByText('回测失败')).toBeInTheDocument()
    expect(screen.getByText('timeout')).toBeInTheDocument()
    expect(screen.getByText('重试')).toBeInTheDocument()
  })

  it('PENDING 显进行中', () => {
    mockTask.mockReturnValue({
      data: { status: 'PENDING', reportId: null, errorMessage: null },
      isLoading: false,
      error: null,
    })
    render(<BacktestResultPanel taskId={1} onRetry={vi.fn()} isRetrying={false} />)
    expect(screen.getByText(/进行中/)).toBeInTheDocument()
  })

  it('点查看详情开 Dialog 显 BacktestResultArea', async () => {
    mockTask.mockReturnValue({
      data: { status: 'COMPLETED', reportId: 42, errorMessage: null },
      isLoading: false,
      error: null,
    })
    mockReport.mockReturnValue({
      data: { id: 42, name: 'r42', equityCurve: [], metrics: METRICS, trades: [] },
      isLoading: false,
      error: null,
    })
    render(<BacktestResultPanel taskId={1} onRetry={vi.fn()} isRetrying={false} />)
    fireEvent.click(screen.getByText('查看详情'))
    await waitFor(() => expect(screen.getByTestId('area')).toBeInTheDocument())
  })
})
