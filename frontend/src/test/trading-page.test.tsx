import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { TradingPage } from '@/pages/TradingPage'
import { useAuthStore } from '@/stores/authStore'
import { useUiStore } from '@/stores/uiStore'

// lightweight-charts 在 jsdom 不可用(canvas),mock 掉
vi.mock('@/components/charts/KlineChart', () => ({
  KlineChart: () => <div data-testid="kline-mock" />,
}))

/**
 * TradingPage 组件测(照 brief §完成标准 3 用例)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/trading.ts 提供 orders/positions,
 * handlers/account.ts 提供 accounts/balance)。
 */
async function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <TradingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...utils, user, qc }
}

describe('TradingPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      user: { userId: 1, username: 'demo' },
      accessToken: 'dummy',
    })
    useUiStore.setState({ tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })

  it('渲染 PAPER 模式(banner + BalanceBar + OrderForm)', async () => {
    await renderPage()
    // banner PAPER
    expect(await screen.findByText('模拟盘交易')).toBeInTheDocument()
    // BalanceBar 4 格标签
    expect(screen.getByText('可用')).toBeInTheDocument()
    expect(screen.getByText('冻结')).toBeInTheDocument()
    // OrderForm
    expect(screen.getByText('下单')).toBeInTheDocument()
    expect(screen.getByText('买入 BUY')).toBeInTheDocument()
  })

  it('切到 LIVE:点 LIVE seg → 确认 Dialog → banner 变实盘交易', async () => {
    const { user } = await renderPage()
    // 等 PAPER 渲染完
    await screen.findByText('模拟盘交易')
    // 点 LIVE seg(LIVE · 实盘 按钮)
    const liveBtn = screen.getByRole('button', { name: /LIVE · 实盘/ })
    await user.click(liveBtn)
    // 切 LIVE Dialog 出现
    expect(await screen.findByText('切到 LIVE 实盘')).toBeInTheDocument()
    // 点确认
    await user.click(screen.getByRole('button', { name: '确认切到 LIVE' }))
    // banner 变实盘交易
    await waitFor(() => {
      expect(screen.getByText('实盘交易')).toBeInTheDocument()
    })
    // 会话 flag 置位(本会话不再弹)
    expect(useUiStore.getState().liveConfirmedThisSession).toBe(true)
  })

  it('OrderForm:点 SELL → 下单按钮文案变卖出', async () => {
    const { user } = await renderPage()
    await screen.findByText('模拟盘交易')
    // 初始买入按钮
    expect(screen.getByRole('button', { name: /买入 0\.1 BTC\/USDT/ })).toBeInTheDocument()
    // 点 SELL toggle
    await user.click(screen.getByRole('button', { name: '卖出 SELL' }))
    // 按钮文案变"卖出"(side state 切到 SELL)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /卖出 0\.1 BTC\/USDT/ })).toBeInTheDocument()
    })
  })
})
