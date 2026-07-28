import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TradingPage } from '@/pages/TradingPage'
import { useAuthStore } from '@/stores/authStore'
import { useUiStore } from '@/stores/uiStore'

// lightweight-charts 在 jsdom 不可用(canvas),mock 掉(对齐 trading-page.test.tsx)
vi.mock('@/components/charts/KlineChart', () => ({
  KlineChart: () => <div data-testid="kline-mock" />,
}))

function renderAt(path: string) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <TradingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('TradingPage 写策略按钮(合约态堵错误入口)', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      user: { userId: 1, username: 'demo' },
      accessToken: 'dummy',
    })
    useUiStore.setState({ tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })

  it('SPOT 态显示写策略按钮', async () => {
    renderAt('/trade?symbol=BTC-USDT&marketType=SPOT')
    expect(await screen.findByRole('link', { name: /写策略/ })).toBeInTheDocument()
  })

  it('PERP 态隐藏写策略按钮', async () => {
    renderAt('/trade?symbol=BTC-USDT&marketType=PERP')
    await waitFor(() => {
      expect(screen.queryByRole('link', { name: /写策略/ })).not.toBeInTheDocument()
    })
  })
})
