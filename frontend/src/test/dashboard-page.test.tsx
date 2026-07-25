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
    // Mock 策略全是实盘交易所(BINANCE/OKX/BITGET),用 LIVE 模式才能看到
    useUiStore.setState({ tradeMode: 'LIVE', liveConfirmedThisSession: true })
  })
  it('渲染 Hero / 旅程 5 步 / 策略卡 / 实时动态 feed / 组合权益曲线 + 4 Stat', async () => {
    renderWithProviders(<DashboardPage />)

    // Hero(标题,动态文案:有策略时"欢迎回来")
    await waitFor(() => expect(screen.getByText(/欢迎回来/)).toBeInTheDocument())

    // Journey 5 步(Hero 按钮文案可能与 Journey 文本重名,用 getAllByText)
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

    // 实时动态 feed(接 activity-feed API,无 mock 时显示"暂无动态")
    expect(screen.getByText('实时动态')).toBeInTheDocument()

    // 组合权益曲线 + 4 Stat(接 trade-history/stats 真实数据)
    expect(screen.getByText('组合权益曲线')).toBeInTheDocument()
    expect(screen.getByText('累计盈亏')).toBeInTheDocument()
    expect(screen.getByText('交易天数')).toBeInTheDocument()
    expect(screen.getByText('按日胜率')).toBeInTheDocument()
    expect(screen.getByText('累计手续费')).toBeInTheDocument()
  })

  it('PAUSED 策略"启动"按钮 → StartDialog(选账户) → 启动 → mutation 触发 dialog 关闭', async () => {
    renderWithProviders(<DashboardPage />)
    // 等 strategies 加载完
    await waitFor(() => expect(screen.getByText('Grid Scalper')).toBeInTheDocument())
    // Grid Scalper 是 PAUSED,显示"启动"按钮(唯一,Hero 是"打开交易"不含"启动")
    const startBtn = screen.getByRole('button', { name: /启动/ })
    fireEvent.click(startBtn)
    // StartDialog 弹出(选账户:title "启动策略")
    expect(await screen.findByText('启动策略')).toBeInTheDocument()
    // dialog 启动按钮:findAllByRole 取最后一个(StrategyRow 启动按钮在前,dialog 启动按钮在后)
    const dialogStartBtn = (await screen.findAllByRole('button', { name: /启动/ })).pop()!
    fireEvent.click(dialogStartBtn)
    // mutation onSuccess → setStartTarget(null) → StartDialog 关闭(覆盖 start→mutate→success)
    await waitFor(() => {
      expect(screen.queryByText('启动策略')).not.toBeInTheDocument()
    })
  })

  it('RUNNING 策略的"暂停"按钮弹出 destructive ConfirmDialog', async () => {
    renderWithProviders(<DashboardPage />)
    // ETH Mean Reversion 只在 StrategyRow(不重名),等策略卡渲染
    await waitFor(() => expect(screen.getByText('ETH Mean Reversion')).toBeInTheDocument())
    // 3 个 RUNNING 策略都有"暂停"按钮(用文本匹配,避开 button AccessibleName 计算差异)
    const pauseEls = await screen.findAllByText('暂停')
    expect(pauseEls.length).toBeGreaterThanOrEqual(1)
    fireEvent.click(pauseEls[0]!)
    expect(await screen.findByText('确认暂停策略')).toBeInTheDocument()
  })
})
