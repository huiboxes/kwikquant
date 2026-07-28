import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'
import { SymbolSelect } from './SymbolSelect'

function wrap(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{ui}</QueryClientProvider>
}

const TICKERS = [
  { ticker: { symbol: 'BTC/USDT', quoteVolume: 48200000000 }, stale: false },
  { ticker: { symbol: 'ETH/USDT', quoteVolume: 22100000000 }, stale: false },
  { ticker: { symbol: 'SOL/USDT', quoteVolume: 8700000000 }, stale: false },
]

describe('SymbolSelect', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/market/tickers', () => HttpResponse.json(envelope(TICKERS))),
    )
  })

  it('renders stripped current symbol as trigger label', async () => {
    render(wrap(<SymbolSelect value="BTC/USDT" onChange={() => {}} exchange="BINANCE" marketType="SPOT" trigger="dialog" />))
    expect(await screen.findByText('BTC/USDT')).toBeInTheDocument()
  })

  it('filters options by search input', async () => {
    render(wrap(<SymbolSelect value="BTC/USDT" onChange={() => {}} exchange="BINANCE" marketType="SPOT" trigger="dialog" />))
    fireEvent.click(await screen.findByText('BTC/USDT'))
    const input = await screen.findByPlaceholderText('搜索标的…')
    fireEvent.change(input, { target: { value: 'ETH' } })
    await waitFor(() => {
      expect(screen.getByText('ETH/USDT')).toBeInTheDocument()
      expect(screen.queryByText('SOL/USDT')).not.toBeInTheDocument()
    })
  })

  it('shows 24h volume formatted as CN compact next to each option', async () => {
    render(wrap(<SymbolSelect value="BTC/USDT" onChange={() => {}} exchange="BINANCE" marketType="SPOT" trigger="dialog" />))
    fireEvent.click(await screen.findByText('BTC/USDT'))
    // 48200000000 → formatMoneyCN → "482亿"
    await waitFor(() => expect(screen.getByText('482亿')).toBeInTheDocument())
  })

  it('calls onChange with stripped symbol on select', async () => {
    const onChange = vi.fn()
    render(wrap(<SymbolSelect value="BTC/USDT" onChange={onChange} exchange="BINANCE" marketType="SPOT" trigger="dialog" />))
    fireEvent.click(await screen.findByText('BTC/USDT'))
    const eth = await screen.findByText('ETH/USDT')
    fireEvent.click(eth)
    expect(onChange).toHaveBeenCalledWith('ETH/USDT')
  })
})
