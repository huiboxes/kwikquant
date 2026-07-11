import { describe, it, expect } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { DashboardPage } from '@/pages/DashboardPage'

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
  it('渲染 Hero / 旅程 5 步 / 策略卡 / 实时动态 feed / 组合权益曲线 + 4 Stat', async () => {
    renderWithProviders(<DashboardPage />)

    // Hero(标题 + Chip 旅程进行中)
    await waitFor(() => expect(screen.getByText(/欢迎回来/)).toBeInTheDocument())
    expect(screen.getByText(/旅程进行中/)).toBeInTheDocument()

    // Journey 5 步
    expect(screen.getByText('编码策略')).toBeInTheDocument()
    expect(screen.getByText('回测验证')).toBeInTheDocument()
    expect(screen.getByText('模拟验证')).toBeInTheDocument()
    expect(screen.getByText('实盘上线')).toBeInTheDocument()
    expect(screen.getByText('持续监控')).toBeInTheDocument()

    // 策略卡(BTC Trend Rider / ETH Mean Reversion / SOL 做市 / Grid Scalper / Funding Arb from mock)
    await waitFor(() => expect(screen.getByText('BTC Trend Rider')).toBeInTheDocument())
    expect(screen.getByText('ETH Mean Reversion')).toBeInTheDocument()
    expect(screen.getByText('Grid Scalper')).toBeInTheDocument()
    expect(screen.getByText('Funding Arb')).toBeInTheDocument()

    // 实时动态 feed(硬编码 6 条)
    expect(screen.getByText(/BTC\/USDT BUY 0.42 @ 61200/)).toBeInTheDocument()
    expect(screen.getByText(/风控拦截 o-9006/)).toBeInTheDocument()

    // 组合权益曲线 + 4 Stat(honest 占位)
    expect(screen.getByText('组合权益曲线')).toBeInTheDocument()
    expect(screen.getByText('累计收益')).toBeInTheDocument()
    expect(screen.getByText('夏普比率')).toBeInTheDocument()
    expect(screen.getByText('最大回撤')).toBeInTheDocument()
    expect(screen.getByText('胜率')).toBeInTheDocument()
  })

  it('PAUSED 策略"启动"按钮 → ConfirmDialog → 确认 → mutation 触发 dialog 关闭', async () => {
    renderWithProviders(<DashboardPage />)
    // 等 strategies 加载完
    await waitFor(() => expect(screen.getByText('Grid Scalper')).toBeInTheDocument())
    // Grid Scalper 是 PAUSED,显示"启动"按钮(唯一,Hero 是"打开交易"不含"启动")
    const startBtn = screen.getByRole('button', { name: /启动/ })
    fireEvent.click(startBtn)
    // ConfirmDialog 弹出
    expect(await screen.findByText('确认启动策略')).toBeInTheDocument()
    expect(screen.getByText(/启动 Grid Scalper/)).toBeInTheDocument()
    // 确认按钮:用文本匹配(避开 AccessibleName 计算差异,同暂停按钮)。
    // click startBtn 后 2 个"启动"文本:[0] StrategyRow(PAUSED 启动按钮),[1] ConfirmDialog 确认
    const confirmBtn = (await screen.findAllByText('启动'))[1]!
    fireEvent.click(confirmBtn)
    // mutation onSuccess → setStartTarget(null) → ConfirmDialog 关闭(覆盖 confirm→mutate→success)
    await waitFor(() => {
      expect(screen.queryByText('确认启动策略')).not.toBeInTheDocument()
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
