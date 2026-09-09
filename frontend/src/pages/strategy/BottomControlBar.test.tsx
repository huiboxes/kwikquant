import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
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
