import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { TradingPage } from '@/pages/TradingPage'
import { useAuthStore } from '@/stores/authStore'
import { useUiStore } from '@/stores/uiStore'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

// lightweight-charts 在 jsdom 不可用(canvas),mock 掉
vi.mock('@/components/charts/KlineChart', () => ({
  KlineChart: () => <div data-testid="kline-mock" />,
}))

/**
 * TradingPage 组件测(Task 5 改造后:删 banner,首元素 BalanceBar;文案过滤;空账户引导)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/trading.ts orders/positions,handlers/account.ts accounts/balance/reset)。
 * mode 由 useUiStore.tradeMode 驱动(切 LIVE 不再走 TradingPage SegMode,归 TopBar TradeModeToggle)。
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

  it('PAPER 模式:首元素 BalanceBar,无 banner/SegMode/重置按钮,OrderForm 在', async () => {
    await renderPage()
    // banner 标题已删
    expect(screen.queryByText('模拟盘交易')).not.toBeInTheDocument()
    // BalanceBar 4 格
    expect(await screen.findByText('可用')).toBeInTheDocument()
    expect(screen.getByText('冻结')).toBeInTheDocument()
    // OrderForm
    expect(screen.getByText('下单')).toBeInTheDocument()
    // 无重置按钮(归 Settings)
    expect(screen.queryByRole('button', { name: /重置模拟盘/ })).not.toBeInTheDocument()
    // 无 SegMode 大按钮
    expect(screen.queryByRole('button', { name: /LIVE · 实盘/ })).not.toBeInTheDocument()
  })

  it('LIVE 模式(setStore,不走 SegMode):实盘渲染,无 SegMode', async () => {
    useUiStore.setState({ tradeMode: 'LIVE', liveConfirmedThisSession: true })
    await renderPage()
    // OrderForm 实盘提示文案(等 accounts 加载后 OrderForm 渲染)
    expect(await screen.findByText(/实盘订单为真金白银/)).toBeInTheDocument()
    // 仍无 SegMode 大按钮
    expect(screen.queryByRole('button', { name: /LIVE · 实盘/ })).not.toBeInTheDocument()
    // LIVE 模式也不泄露风控规则名 / 风控闸门 实现细节
    expect(screen.queryByText(/MAX_NOTIONAL|DAILY_LOSS_LIMIT|ORDER_FREQUENCY/)).not.toBeInTheDocument()
    expect(screen.queryByText(/风控闸门/)).not.toBeInTheDocument()
  })

  it('空账户引导:LIVE 模式 + 无实盘账户 → EmptyState 去添加', async () => {
    // 覆写 accounts 只返 PAPER 账户 → LIVE 模式 modeAccounts 空
    server.use(
      http.get('/api/v1/accounts', () =>
        HttpResponse.json(
          envelope([
            { id: 1, exchange: 'BINANCE', label: 'BINANCE 模拟', apiKey: '', paperTrading: true, status: 'ACTIVE' },
          ]),
        ),
      ),
    )
    useUiStore.setState({ tradeMode: 'LIVE', liveConfirmedThisSession: true })
    await renderPage()
    expect(await screen.findByText(/还没有实盘账户/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /去添加/ })).toBeInTheDocument()
  })

  it('文案:不泄露风控规则名 + 余额来源实现细节', async () => {
    await renderPage()
    expect(screen.queryByText(/MAX_NOTIONAL|DAILY_LOSS_LIMIT|ORDER_FREQUENCY/)).not.toBeInTheDocument()
    expect(screen.queryByText(/余额由交易所|本地真实化|基准行情|行情撮合/)).not.toBeInTheDocument()
  })

  it('OrderForm:点 SELL → 下单按钮文案变卖出', async () => {
    const { user } = await renderPage()
    await screen.findByText('可用') // 等 PAPER 渲染稳(不再依赖 banner)
    expect(screen.getByRole('button', { name: /买入 0\.1 BTC\/USDT/ })).toBeInTheDocument()
    // BUY/SELL 已改为 Tabs(交互同行情页现货/合约切换),文案纯中文(不暴露枚举)
    await user.click(screen.getByRole('tab', { name: '卖出' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /卖出 0\.1 BTC\/USDT/ })).toBeInTheDocument()
    })
  })

  it('K 线 header:interval 6 档 TabsTrigger + 写策略 link 含 sel', async () => {
    await renderPage()
    await screen.findByText('可用') // 等 PAPER 渲染稳
    // interval 6 档(默认 15m active,其余 tab 也在)
    expect(screen.getByRole('tab', { name: '15m' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '1h' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '1d' })).toBeInTheDocument()
    // 写策略 link href 含 symbol=BTC/USDT(默认 sel)
    const link = screen.getByRole('link', { name: /写策略/ })
    expect(link.getAttribute('href') ?? '').toContain('symbol=BTC')
  })

  it('?symbol=ETH/USDT query → K 线标题显 ETH/USDT + 写策略 link 含 ETH', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } })
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/trade?symbol=ETH/USDT']}>
          <TradingPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    // K 线标题显 ETH/USDT(sel 从 query)
    expect(await screen.findByText(/ETH\/USDT · K 线/)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /写策略/ })
    expect(link.getAttribute('href') ?? '').toContain('symbol=ETH')
  })
})
