import { describe, it, expect } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useBacktestTask } from './useBacktestTask'

function createWrapper() {
  // refetchIntervalInBackground: false 避免后台轮询干扰断言
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

describe('useBacktestTask', () => {
  it('taskId=null → enabled false,不请求(data undefined, isLoading false)', () => {
    const { result } = renderHook(() => useBacktestTask(null), { wrapper: createWrapper() })
    expect(result.current.data).toBeUndefined()
    expect(result.current.isLoading).toBe(false)
  })

  it('GET /backtests/6001 第一次返 RUNNING(序列从 PENDING 推进)', async () => {
    const { result } = renderHook(() => useBacktestTask(6001), { wrapper: createWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.id).toBe(6001)
    // handler 序列:首次 GET 后 status=RUNNING(从 PENDING 推进)
    expect(result.current.data?.status).toBe('RUNNING')
  })

  it('FAILED fixture(9999) → status=FAILED + errorMessage', async () => {
    const { result } = renderHook(() => useBacktestTask(9999), { wrapper: createWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.status).toBe('FAILED')
    expect(result.current.data?.errorMessage).toBeTruthy()
  })

  it('不存在 task(8888) → 404 错误(7201)', async () => {
    const { result } = renderHook(() => useBacktestTask(8888), { wrapper: createWrapper() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBeDefined()
  })
})
