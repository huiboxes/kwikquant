import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
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

  it('组合模式选满 ≥2 标的才能提交,range 携带 symbols', async () => {
    const onSubmit = vi.fn()
    renderBar('PERP', onSubmit)
    // 切到组合模式
    fireEvent.click(await screen.findByTestId('backtest-mode-portfolio'))
    // 未选标的:回测按钮禁用
    const runBtn = screen.getByTestId('backtest-run-btn')
    expect(runBtn).toBeDisabled()
    // 打开多选器,选 2 个标的(多选不关闭浮层)
    fireEvent.click(screen.getByTestId('multi-symbol-trigger'))
    fireEvent.click(await screen.findByText('BTC/USDT'))
    expect(runBtn).toBeDisabled() // 只有 1 个仍禁
    fireEvent.click(screen.getByText('ETH/USDT'))
    // 关浮层后提交
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    await screen.findByText('BTC/USDT +1') // trigger 汇总标签
    fireEvent.click(runBtn)
    expect(onSubmit).toHaveBeenCalledTimes(1)
    const range: BacktestRange = onSubmit.mock.calls[0][0]
    expect(range.symbols).toEqual(['BTC/USDT', 'ETH/USDT'])
  })

  it('单标的模式提交 range 不带 symbols(存量行为回归)', async () => {
    const onSubmit = vi.fn()
    renderBar('SPOT', onSubmit)
    fireEvent.click(await screen.findByRole('button', { name: /回测/ }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit.mock.calls[0][0].symbols).toBeUndefined()
  })
})
