import { describe, it, expect } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'
import { useTradableSymbols } from './useMarket'

describe('useTradableSymbols', () => {
  it('returns {symbol, quoteVolume}[] with empty symbol filtered', async () => {
    // 临时覆盖 /market/tickers:塞一个空 symbol 项验证过滤
    server.use(
      http.get('/api/v1/market/tickers', () =>
        HttpResponse.json(
          envelope([
            { ticker: { symbol: 'BTC/USDT', quoteVolume: 750000000 }, stale: false },
            { ticker: { symbol: '', quoteVolume: 100 }, stale: false }, // 空,应被过滤
            { ticker: { symbol: 'ETH/USDT', quoteVolume: 298000000 }, stale: false },
          ]),
        ),
      ),
    )
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useTradableSymbols('BINANCE', 'SPOT'), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const data = result.current.data!
    expect(data).toHaveLength(2) // 空 symbol 过滤掉
    expect(data.every((s) => s.symbol !== '')).toBe(true)
    expect(data.some((s) => s.symbol === 'BTC/USDT')).toBe(true)
    expect(data.every((s) => typeof s.quoteVolume === 'number')).toBe(true)
  })
})
