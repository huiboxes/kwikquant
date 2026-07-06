import { describe, it, expect } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useSubmitBacktest } from './useSubmitBacktest'

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return wrapper
}

const React = await import('react')
void React

const validInput = {
  strategyId: 1,
  symbol: 'BTC/USDT',
  exchange: 'BINANCE',
  intervalValue: '1h',
  startTime: '2026-06-01T00:00:00Z',
  endTime: '2026-07-01T00:00:00Z',
  parameters: { initial_capital: 10000 },
}

describe('useSubmitBacktest', () => {
  it('提交成功 → 返 BacktestTaskDto(status=PENDING, id>0)', async () => {
    const { result } = renderHook(() => useSubmitBacktest(), { wrapper: createWrapper() })
    await act(async () => {
      result.current.mutate(validInput)
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.status).toBe('PENDING')
    expect(result.current.data?.id).toBeGreaterThan(0)
    expect(result.current.data?.strategyId).toBe(1)
  })

  it('parameters 对象被 JSON.stringify 发送(task.parameters 是字符串)', async () => {
    const { result } = renderHook(() => useSubmitBacktest(), { wrapper: createWrapper() })
    await act(async () => {
      result.current.mutate(validInput)
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // handler 回填 task.parameters = body.parameters(JSON 字符串)
    expect(result.current.data?.parameters).toBe(JSON.stringify({ initial_capital: 10000 }))
  })

  it('symbol/exchange/intervalValue 透传到 task', async () => {
    const { result } = renderHook(() => useSubmitBacktest(), { wrapper: createWrapper() })
    await act(async () => {
      result.current.mutate({ ...validInput, symbol: 'ETH/USDT', exchange: 'OKX' })
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.symbol).toBe('ETH/USDT')
    expect(result.current.data?.exchange).toBe('OKX')
  })
})
