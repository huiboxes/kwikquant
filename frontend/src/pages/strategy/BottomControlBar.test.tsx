import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'
import { BottomControlBar } from './BottomControlBar'
import type { BacktestRange } from './BottomControlBar'

/**
 * BottomControlBar — PERP 资金费代理开关(默认关 = fail-closed,开启是显式决定)。
 * SPOT 策略不渲染开关;提交时 range 携带 allowFundingProxy。
 */
function renderBar(marketType: string, onSubmit: (range: BacktestRange) => void) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <BottomControlBar
          symbol="BTC/USDT:USDT"
          interval="1h"
          strategySymbol="BTC/USDT:USDT"
          strategyInterval="1h"
          strategyExchange="OKX"
          exchange="OKX"
          marketType={marketType}
          backtesting={false}
          onSubmitBacktest={onSubmit}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BottomControlBar 资金费代理开关', () => {
  it('PERP 策略渲染开关,默认关闭(fail-closed)', async () => {
    renderBar('PERP', vi.fn())
    const sw = await screen.findByTestId('funding-proxy-switch')
    expect(sw).toBeInTheDocument()
    expect(sw).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('资金费代理')).toBeInTheDocument()
  })

  it('SPOT 策略不渲染开关', async () => {
    renderBar('SPOT', vi.fn())
    await screen.findByRole('button', { name: /回测/ })
    expect(screen.queryByTestId('funding-proxy-switch')).not.toBeInTheDocument()
  })

  it('不开开关提交 → allowFundingProxy=false', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderBar('PERP', onSubmit)
    await user.click(await screen.findByRole('button', { name: /回测/ }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0].allowFundingProxy).toBe(false)
  })

  it('开启开关后提交 → allowFundingProxy=true(显式决定)', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    renderBar('PERP', onSubmit)
    const sw = await screen.findByTestId('funding-proxy-switch')
    await user.click(sw)
    expect(sw).toHaveAttribute('aria-checked', 'true')
    await user.click(screen.getByRole('button', { name: /回测/ }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0].allowFundingProxy).toBe(true)
  })
})

describe('BottomControlBar 组合(多标的)回测', () => {
  const TICKERS = [
    { ticker: { symbol: 'BTC/USDT', quoteVolume: 48200000000 }, stale: false },
    { ticker: { symbol: 'ETH/USDT', quoteVolume: 22100000000 }, stale: false },
    { ticker: { symbol: 'SOL/USDT', quoteVolume: 8700000000 }, stale: false },
  ]

  beforeEach(() => {
    server.use(http.get('/api/v1/market/tickers', () => HttpResponse.json(envelope(TICKERS))))
  })

  it('组合模式选满 ≥2 才能提交:禁用期内联出声提示原因,range 携带 symbols', async () => {
    const onSubmit = vi.fn()
    renderBar('PERP', onSubmit)
    // 切到组合模式
    fireEvent.click(await screen.findByTestId('backtest-mode-portfolio'))
    // 未选标的:回测按钮禁用(disabled 按钮 title 不弹,提示必须内联可见)
    const runBtn = screen.getByTestId('backtest-run-btn')
    expect(runBtn).toBeDisabled()
    expect(screen.getByText('再选 2 个标的即可提交')).toBeInTheDocument()
    // 打开多选器,选标的(多选不关闭浮层)
    fireEvent.click(screen.getByTestId('multi-symbol-trigger'))
    fireEvent.click(await screen.findByText('BTC/USDT'))
    expect(runBtn).toBeDisabled() // 只有 1 个仍禁
    expect(screen.getByText('再选 1 个标的即可提交')).toBeInTheDocument()
    fireEvent.click(screen.getByText('ETH/USDT'))
    expect(screen.queryByText(/再选 \d 个标的即可提交/)).not.toBeInTheDocument()
    // 关浮层后提交
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    await screen.findByText('BTC/USDT +1') // trigger 汇总标签
    fireEvent.click(runBtn)
    expect(onSubmit).toHaveBeenCalledTimes(1)
    const range: BacktestRange = onSubmit.mock.calls[0][0]
    expect(range.symbols).toEqual(['BTC/USDT', 'ETH/USDT'])
  })

  it('retry 组合 A → retry 单标的 B:initialSymbols 置 null 退出组合模式(泄漏回归)', async () => {
    // 架构师 P1-1:同挂载内两次 retry(路由参数变化不重挂),单标的 retry 若不清组合
    // 预填,用户点回测会静默重提上一次的组合清单
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const ui = (symbols: string[] | null) => (
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <BottomControlBar
            symbol="BTC/USDT"
            interval="1h"
            strategySymbol="BTC/USDT"
            strategyInterval="1h"
            strategyExchange="OKX"
            exchange="OKX"
            marketType="PERP"
            backtesting={false}
            onSubmitBacktest={vi.fn()}
            initialSymbols={symbols}
          />
        </MemoryRouter>
      </QueryClientProvider>
    )
    const { rerender } = render(ui(['BTC/USDT:USDT', 'ETH/USDT:USDT']))
    expect(await screen.findByTestId('multi-symbol-trigger')).toBeInTheDocument()
    expect(screen.getByTestId('backtest-mode-portfolio')).toHaveAttribute('aria-checked', 'true')
    rerender(ui(null))
    await waitFor(() =>
      expect(screen.queryByTestId('multi-symbol-trigger')).not.toBeInTheDocument(),
    )
    expect(screen.getByTestId('backtest-mode-portfolio')).toHaveAttribute('aria-checked', 'false')
  })

  it('单标的模式提交 range 不带 symbols(存量行为回归)', async () => {
    const onSubmit = vi.fn()
    renderBar('SPOT', onSubmit)
    fireEvent.click(await screen.findByRole('button', { name: /回测/ }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0].symbols).toBeUndefined()
  })
})
