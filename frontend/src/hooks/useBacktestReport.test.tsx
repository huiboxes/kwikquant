import { describe, it, expect } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useBacktestReport } from './useBacktestReport'

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchIntervalInBackground: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return wrapper
}

const React = await import('react')
void React

describe('useBacktestReport', () => {
  it('reportId=null → enabled false,不请求(data undefined)', () => {
    const { result } = renderHook(() => useBacktestReport(null), {
      wrapper: createWrapper(),
    })
    expect(result.current.data).toBeUndefined()
    expect(result.current.isLoading).toBe(false)
  })

  it('reportId=0 → enabled false,不请求(m2 防御)', () => {
    const { result } = renderHook(() => useBacktestReport(0), {
      wrapper: createWrapper(),
    })
    expect(result.current.data).toBeUndefined()
    expect(result.current.isLoading).toBe(false)
  })

  it('GET /reports/42 → 返 BacktestReportDetailDto(metrics/equityCurve)', async () => {
    const { result } = renderHook(() => useBacktestReport(42), {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.id).toBe(42)
    expect(result.current.data?.metrics.totalReturn).toBe(0.1532)
    expect(result.current.data?.equityCurve).toHaveLength(4)
    expect(result.current.data?.trades).toHaveLength(2)
  })

  it('不存在 report(9999) → 404 error(9001)', async () => {
    const { result } = renderHook(() => useBacktestReport(9999), {
      wrapper: createWrapper(),
    })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBeDefined()
  })
})
