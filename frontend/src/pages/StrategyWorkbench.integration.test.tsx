import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { StrategyWorkbench } from './StrategyWorkbench'

// mock MonacoEditor(JSDOM 不支持 Monaco web worker)
vi.mock('@/components/MonacoEditor', () => ({
  MonacoEditor: ({ value }: { value: string }) => (
    <div data-testid="monaco">{value}</div>
  ),
}))
// mock lightweight-charts(JSDOM 不支持 canvas)
vi.mock('lightweight-charts', () => ({
  createChart: vi.fn(() => ({
    addSeries: vi.fn(() => ({ setData: vi.fn(), applyOptions: vi.fn() })),
    applyOptions: vi.fn(),
    timeScale: () => ({ fitContent: vi.fn() }),
    remove: vi.fn(),
  })),
  AreaSeries: {},
}))

// JSDOM 缺 ResizeObserver/MutationObserver(EquityChart 用)
class RO {
  observe() {}
  disconnect() {}
}
class MO {
  observe() {}
  disconnect() {}
}
beforeAll(() => {
  vi.stubGlobal('ResizeObserver', RO)
  vi.stubGlobal('MutationObserver', MO)
})

const METRICS = {
  totalReturn: 0.1,
  sharpeRatio: 1,
  maxDrawdown: -0.05,
  winRate: 0.5,
  profitFactor: 1,
  totalTrades: 5,
  avgTradeDurationSeconds: 3600,
}

function renderWith(initial: string) {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchIntervalInBackground: false },
    },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initial]}>
        <StrategyWorkbench />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('StrategyWorkbench 集成', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/strategies', () =>
        HttpResponse.json({
          code: 0,
          data: [
            {
              id: 1,
              name: 'strategy-1',
              description: '',
              symbol: 'BTC/USDT',
            },
          ],
        }),
      ),
      http.get('/api/v1/strategies/:id/codes', () =>
        HttpResponse.json({
          code: 0,
          data: [{ id: 1, status: 'DRAFT', sourceCode: 'print(1)' }],
        }),
      ),
      http.get('/api/v1/strategies/:id/codes/:codeId', () =>
        HttpResponse.json({
          code: 0,
          data: { id: 1, sourceCode: 'print(1)', status: 'DRAFT' },
        }),
      ),
      http.post('/api/v1/backtests', () =>
        HttpResponse.json({ code: 0, data: { id: 99 } }),
      ),
      http.get('/api/v1/backtests/:taskId', () =>
        HttpResponse.json({
          code: 0,
          data: { id: 99, status: 'COMPLETED', reportId: 42, errorMessage: null },
        }),
      ),
      http.get('/api/v1/reports/:id', () =>
        HttpResponse.json({
          code: 0,
          data: {
            id: 42,
            name: 'r42',
            equityCurve: [],
            metrics: METRICS,
            trades: [],
          },
        }),
      ),
      http.get('/api/v1/ai/keys', () => HttpResponse.json({ code: 0, data: [] })),
    )
  })

  it('加载策略→显 TabBar strategy-1.py + 编辑器 + 右栏', async () => {
    renderWith('/workbench?tabs=1&active=1')
    await waitFor(() =>
      expect(screen.getByText('strategy-1.py')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('monaco')).toBeInTheDocument()
  })

  it('点 Backtest 弹 AlertDialog 确认', async () => {
    renderWith('/workbench?tabs=1&active=1')
    await waitFor(() =>
      expect(screen.getByText('strategy-1.py')).toBeInTheDocument(),
    )
    fireEvent.click(screen.getByText('Backtest'))
    await waitFor(() =>
      expect(screen.getByText(/用所选参数跑回测/)).toBeInTheDocument(),
    )
  })
})
