import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

// KlineChart 用 lightweight-charts,jsdom 无 canvas getContext 报错(Value is null)。
// mock 占位避免 unhandled error(KlineChart 逻辑由 lightweight-charts 保证,测试不验证图本身)。
vi.mock('@/components/charts/KlineChart', () => ({
  KlineChart: ({ height }: { height?: number }) => (
    <div data-testid="kline-mock" style={{ height }} />
  ),
}))

import { MarketPage } from '@/pages/MarketPage'

function renderWithProviders(ui: React.ReactElement) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MarketPage', () => {
  it('渲染 Ticker grid / K 线 / 订单簿 / 订阅状态 / PAPER 来源 / Heatmap', async () => {
    renderWithProviders(<MarketPage />)

    // Header
    expect(await screen.findByText('行情')).toBeInTheDocument()

    // Ticker grid(8 symbol,BINANCE SPOT,产品精选非 mock)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    expect(screen.getAllByText('ETH/USDT').length).toBeGreaterThan(0)
    expect(screen.getAllByText('DOGE/USDT').length).toBeGreaterThan(0)

    // K 线区 + 订单簿
    expect(screen.getByText('订单簿深度')).toBeInTheDocument()
    // K 线 timeframe tabs
    expect(screen.getByRole('tab', { name: '15m' })).toBeInTheDocument()

    // 订阅状态 + PAPER 来源
    expect(screen.getByText('订阅状态')).toBeInTheDocument()
    expect(screen.getByText('PAPER 行情来源')).toBeInTheDocument()

    // 板块涨跌热度
    expect(screen.getByText('板块涨跌热度')).toBeInTheDocument()
  })

  it('XRP stale:true 显示 STALE 徽章 + 订阅状态"断开"', async () => {
    renderWithProviders(<MarketPage />)
    // 等 XRP ticker data 到(stale=true → TickerCard STALE Chip + 订阅状态"断开")
    expect(await screen.findAllByText('STALE')).toHaveLength(1)
    expect(screen.getByText('断开')).toBeInTheDocument()
  })

  it('点 ETH ticker 卡 → 切 sel → K 线区 + 订单簿 symbol 变 ETH/USDT', async () => {
    renderWithProviders(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('ETH/USDT').length).toBeGreaterThan(0))
    // 初始 sel = BTC/USDT(K 线区标题 + 订单簿 symbol)
    // 点 ETH ticker 卡(TickerCard 是 button,含 ETH/USDT 文本)
    const ethCard = screen.getAllByText('ETH/USDT')[0]!
    fireEvent.click(ethCard)
    // K 线区 + 订单簿 symbol 切到 ETH/USDT(订单簿 div symbol text)
    // ETH/USDT 在多处(TickerCard + 订阅状态 + K线标题 + 订单簿),切后数量增加
    await waitFor(() => {
      expect(screen.getAllByText('ETH/USDT').length).toBeGreaterThan(2)
    })
  })
})
