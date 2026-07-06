import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { BacktestResultArea, BacktestReportView } from './BacktestResultArea'

// mock lightweight-charts(JSDOM 不支持 canvas,createChart 会崩;COMPLETED 路径触发 EquityChart)
vi.mock('lightweight-charts', () => ({
  createChart: vi.fn(() => ({
    addSeries: vi.fn(() => ({ setData: vi.fn(), applyOptions: vi.fn() })),
    timeScale: vi.fn(() => ({ fitContent: vi.fn() })),
    applyOptions: vi.fn(),
    remove: vi.fn(),
  })),
  LineSeries: {},
}))

// JSDOM 缺 ResizeObserver,polyfill(EquityChart 用)
class MockResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
globalThis.ResizeObserver = MockResizeObserver as unknown as typeof ResizeObserver

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchIntervalInBackground: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return wrapper
}

const React = await import('react')
void React

describe('BacktestResultArea', () => {
  it('taskId=null → 占位文案"提交回测后在此查看结果"', () => {
    render(<BacktestResultArea taskId={null} />, { wrapper: createWrapper() })
    expect(screen.getByText(/提交回测后在此查看结果/)).toBeInTheDocument()
  })

  it('FAILED(9999) → 渲染 errorMessage + 重新提交按钮', async () => {
    const onRetry = vi.fn()
    render(<BacktestResultArea taskId={9999} onRetry={onRetry} />, {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(screen.getByText('回测失败')).toBeInTheDocument())
    expect(screen.getByText(/回测引擎异常/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新提交' })).toBeInTheDocument()
  })

  it('FAILED → 点重新提交按钮触发 onRetry', async () => {
    const onRetry = vi.fn()
    render(<BacktestResultArea taskId={9999} onRetry={onRetry} />, {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(screen.getByText('回测失败')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '重新提交' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('FAILED + isRetrying → 按钮 disabled + 文案"提交中…"', async () => {
    render(
      <BacktestResultArea taskId={9999} onRetry={() => {}} isRetrying />,
      { wrapper: createWrapper() },
    )
    await waitFor(() => expect(screen.getByText('回测失败')).toBeInTheDocument())
    const btn = screen.getByRole('button', { name: '提交中…' })
    expect(btn).toBeDisabled()
  })

  it('PENDING/RUNNING(6001) → 渲染"回测进行中"', async () => {
    render(<BacktestResultArea taskId={6001} />, { wrapper: createWrapper() })
    await waitFor(() => expect(screen.getByText('回测进行中…')).toBeInTheDocument())
  })

  it(
    'COMPLETED(6001 第二次轮询) → 渲染 Report #42 + 指标 + trades',
    async () => {
      render(<BacktestResultArea taskId={6001} />, { wrapper: createWrapper() })
      // 第一次 GET RUNNING,2s 后第二次 GET COMPLETED + reportId=42 → useBacktestReport GET /reports/42
      await waitFor(
        () => expect(screen.getByText(/Report #42/)).toBeInTheDocument(),
        { timeout: 5_000 },
      )
      expect(screen.getByText('核心指标')).toBeInTheDocument()
      expect(screen.getByText('交易明细')).toBeInTheDocument()
    },
    10_000,
  )
})

describe('BacktestReportView', () => {
  it('reportId=0 → 渲染"报告缺失" ErrorState(契约违规 fallback)', () => {
    render(<BacktestReportView reportId={0} />, { wrapper: createWrapper() })
    expect(screen.getByText('报告缺失')).toBeInTheDocument()
  })

  it('reportId=null → 渲染"报告缺失"', () => {
    render(<BacktestReportView reportId={null} />, { wrapper: createWrapper() })
    expect(screen.getByText('报告缺失')).toBeInTheDocument()
  })
})
