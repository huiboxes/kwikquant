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
    // mock 策略全绑模拟盘账户(account 1 paperTrading=true),PAPER 模式才显示策略行
    // 修 Bug B 后按 strategy.exchangeAccountId 反查 accounts.paperTrading 判断模拟盘,
    // 不再用 exchange==='PAPER'(后端模拟盘 exchange 是参考交易所 BINANCE/OKX,永不等于 PAPER)
    useUiStore.setState({ tradeMode: 'PAPER' })
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

  it('PAPER 模式 HeroCard:模拟卡显模拟盘余额,实盘卡显实盘余额(修 Bug A 余额拆分)', async () => {
    renderWithProviders(<DashboardPage />)
    // 等 summary 加载完:模拟卡/实盘卡金额渲染(waitFor 重试至金额出现,覆盖 loading 期)
    // 模拟卡:mock PAPER summary accounts[1,3] USDT total=100000+100000=200000 → formatMoney dp:0 "200,000"
    // 实盘卡:mock LIVE summary accounts[2,4] USDT total=5234.18+890.5=6124.68 → dp:0 "6,125"
    // 修 Bug A 前:模拟卡恒 $ 0,实盘卡=模拟盘余额 $ 200,000 → 两断言均 RED
    await waitFor(() => {
      expect(screen.getByText('模拟').parentElement).toHaveTextContent(/\$ 200,000/)
    })
    expect(screen.getByText('实盘').parentElement).toHaveTextContent(/\$ 6,125/)
  })

  it('PAUSED 策略"启动"按钮 → 直接 resume(用已绑账户,不弹 StartDialog)', async () => {
    renderWithProviders(<DashboardPage />)
    // 等 strategies 加载完
    await waitFor(() => expect(screen.getByText('Grid Scalper')).toBeInTheDocument())
    // Grid Scalper 是 PAUSED,显示"启动"按钮(resume:用已绑账户,不弹 StartDialog)
    const startBtn = screen.getByRole('button', { name: /启动/ })
    fireEvent.click(startBtn)
    // resume 不弹 StartDialog(用已绑账户,最小惊讶);StartDialog title "启动策略" 不出现
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
