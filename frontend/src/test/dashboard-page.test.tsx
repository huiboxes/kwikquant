import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { DashboardPage } from '@/pages/DashboardPage'
import { useUiStore } from '@/stores/uiStore'

/** 包 QueryClientProvider(react-query)+ MemoryRouter(useNavigate),DashboardPage 直接 render 不经 RequireAuth。 */
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

describe('DashboardPage', () => {
  beforeEach(() => {
    // Mock 策略均绑定 id=1 模拟账户，模式以绑定账户 paperTrading 为准。
    useUiStore.setState({ tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })
  it('渲染 Hero / 旅程 5 步 / 策略卡 / 实时动态 feed / 组合权益曲线 + 4 Stat', async () => {
    renderWithProviders(<DashboardPage />)

    // Hero(标题，动态文案：有策略时"欢迎回来")
    await waitFor(() => expect(screen.getByText(/欢迎回来/)).toBeInTheDocument())

    // Journey 5 步(Hero 按钮文案可能与 Journey 文本重名，用 getAllByText)
    expect(screen.getAllByText(/编写策略/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/回测验证/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/模拟验证/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/实盘上线/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/持续监控/).length).toBeGreaterThanOrEqual(1)

    // 策略卡(BTC Trend Rider / ETH Mean Reversion / SOL 做市 / Grid Scalper / Funding Arb from mock)
    await waitFor(() => expect(screen.getByText('BTC Trend Rider')).toBeInTheDocument())
    expect(screen.getByText('ETH Mean Reversion')).toBeInTheDocument()
    expect(screen.getByText('Grid Scalper')).toBeInTheDocument()
    expect(screen.getByText('Funding Arb')).toBeInTheDocument()

    // 实时动态 feed(接 activity-feed API，无 mock 时显示"暂无动态")
    expect(screen.getByText('实时动态')).toBeInTheDocument()

    // 组合权益曲线 + 4 Stat(接 trade-history/stats 真实数据)
    expect(screen.getByText('组合权益曲线')).toBeInTheDocument()
    expect(screen.getByText('累计盈亏')).toBeInTheDocument()
    expect(screen.getByText('交易天数')).toBeInTheDocument()
    expect(screen.getByText('按日胜率')).toBeInTheDocument()
    expect(screen.getByText('累计手续费')).toBeInTheDocument()
  })

  it('PAUSED 策略"启动"按钮 → 直接 resume(用已绑账户，不弹 StartDialog)', async () => {
    renderWithProviders(<DashboardPage />)
    // 等 strategies 加载完
    await waitFor(() => expect(screen.getByText('Grid Scalper')).toBeInTheDocument())
    // Grid Scalper 是 PAUSED，显示"启动"按钮(resume:用已绑账户，不弹 StartDialog)
    const startBtn = screen.getByRole('button', { name: /启动/ })
    fireEvent.click(startBtn)
    // resume 不弹 StartDialog(用已绑账户，最小惊讶);StartDialog title "启动策略" 不出现
    await waitFor(() => {
      expect(screen.queryByText('启动策略')).not.toBeInTheDocument()
    })
  })

  it('RUNNING 策略的"暂停"按钮弹出 destructive ConfirmDialog', async () => {
    renderWithProviders(<DashboardPage />)
    // ETH Mean Reversion 只在 StrategyRow(不重名)，等策略卡渲染
    await waitFor(() => expect(screen.getByText('ETH Mean Reversion')).toBeInTheDocument())
    // 3 个 RUNNING 策略都有"暂停"按钮(用文本匹配，避开 button AccessibleName 计算差异)
    const pauseEls = await screen.findAllByText('暂停')
    expect(pauseEls.length).toBeGreaterThanOrEqual(1)
    fireEvent.click(pauseEls[0]!)
    expect(await screen.findByText('确认暂停策略')).toBeInTheDocument()
  })

  it('策略模式来自绑定账户，而不是行情交易所', async () => {
    renderWithProviders(<DashboardPage />)
    await waitFor(() => expect(screen.getByText('BTC Trend Rider')).toBeInTheDocument())
    expect(screen.getAllByText(/模拟盘 · BINANCE/).length).toBeGreaterThanOrEqual(1)
    expect(screen.queryByText(/账户模式未知/)).not.toBeInTheDocument()
  })

  it('从旅程进入实盘前确认真实资金风险', async () => {
    renderWithProviders(<DashboardPage />)
    await waitFor(() => expect(screen.getByText('BTC Trend Rider')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /实盘上线/ }))
    expect(await screen.findByText('进入实盘交易')).toBeInTheDocument()
    expect(useUiStore.getState().tradeMode).toBe('PAPER')

    fireEvent.click(screen.getByRole('button', { name: '确认进入实盘' }))
    expect(useUiStore.getState().tradeMode).toBe('LIVE')
    expect(useUiStore.getState().liveConfirmedThisSession).toBe(true)
  })
})
