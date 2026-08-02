import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useBacktestList } from '../hooks/useBacktest'

// 全局 msw server(setup.ts beforeAll listen)已注册 backtestHandlers 含 GET /backtests。

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

describe('useBacktestList', () => {
  it('returns all backtests with totalReturn + strategyName', async () => {
    const { result } = renderHook(() => useBacktestList(), { wrapper })
    await waitFor(() => expect(result.current.data).toBeDefined())
    const data = result.current.data!
    expect(data.length).toBeGreaterThanOrEqual(3)
    const completed = data.find((t) => t.id === 2201)!
    expect(completed.totalReturn).toBe(0.1532)
    expect(completed.strategyName).toBe('BTC Trend Rider v1.3.2')
    const running = data.find((t) => t.id === 2203)!
    expect(running.status).toBe('RUNNING')
  })
})
