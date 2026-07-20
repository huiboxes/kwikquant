import { describe, it, expect } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { MarketPage } from '@/pages/MarketPage'

/**
 * MarketPage 组件测(Task C 重写后:移动端交易所风格列表)。
 * MSW handlers/market.ts 提供 GET /market/tickers(batch,8 symbol)+ /accounts。
 * 删(Task D 抽 useKlineChart):K 线/订单簿/订阅状态/来源块 — 不再测。
 */
function renderWith(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

function renderWithProbe(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}<LocationProbe /></MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MarketPage', () => {
  it('渲染行情列表 + 表头三列 + 现货/合约 tab', async () => {
    renderWith(<MarketPage />)
    expect(screen.getByText('行情')).toBeInTheDocument()
    expect(screen.getByText(/币种 \/ 成交额/)).toBeInTheDocument()
    expect(screen.getByText(/最新价/)).toBeInTheDocument()
    expect(screen.getByText(/涨跌幅/)).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '现货' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '合约' })).toBeInTheDocument()
  })

  it('默认 SPOT:列表渲染 BTC/USDT + ETH/USDT(MSW batch handler 返 8 symbol)', async () => {
    renderWith(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    expect(screen.getAllByText('ETH/USDT').length).toBeGreaterThan(0)
  })

  it('搜索 BTC:只显示 BTC/USDT,过滤其他', async () => {
    renderWith(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    fireEvent.change(screen.getByLabelText('搜索币种'), { target: { value: 'BTC' } })
    await waitFor(() => {
      expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0)
      expect(screen.queryByText('ETH/USDT')).not.toBeInTheDocument()
    })
  })

  it('每行有"策"按钮(aria-label 含 symbol),点击 stopPropagation 不 crash', async () => {
    renderWith(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    const btns = screen.getAllByLabelText(/写策略/)
    expect(btns.length).toBeGreaterThan(0)
    fireEvent.click(btns[0]!)
  })

  it('点"最新价"表头 → toggleSort 切 sort=last desc(aria-sort + ↓)', async () => {
    renderWith(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    const lastBtn = screen.getByText(/最新价/)
    fireEvent.click(lastBtn)
    await waitFor(() => {
      expect(lastBtn.getAttribute('aria-sort')).toBe('descending')
      expect(lastBtn.textContent ?? '').toContain('↓')
    })
  })

  it('点行 Link → 跳 /trade?symbol=BTC/USDT&marketType=SPOT', async () => {
    renderWithProbe(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    fireEvent.click(screen.getAllByLabelText(/交易 BTC\/USDT/)[0]!)
    await waitFor(() => {
      const loc = screen.getByTestId('location').textContent ?? ''
      expect(loc).toContain('/trade')
      expect(loc).toContain('symbol=BTC%2FUSDT') // encodeURIComponent('BTC/USDT')
      expect(loc).toContain('marketType=SPOT')
    })
  })

  it('点"策"按钮 Link → 跳 /strategies/new?...(不触发行 Link)', async () => {
    renderWithProbe(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    fireEvent.click(screen.getAllByLabelText(/写策略/)[0]!)
    await waitFor(() => {
      const loc = screen.getByTestId('location').textContent ?? ''
      expect(loc).toContain('/strategies/new')
      expect(loc).toContain('symbol=BTC%2FUSDT')
      expect(loc).toContain('marketType=SPOT')
    })
  })

  it('切合约 tab(PERP)→ useMarketTickers marketType=PERP,仍显示 BTC/USDT', async () => {
    renderWith(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    fireEvent.click(screen.getByRole('tab', { name: '合约' }))
    // MSW batch handler 不论 marketType 返同样 8 canonical symbol,PERP tab 仍显示 BTC/USDT
    await waitFor(() => {
      expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0)
    })
  })
})
