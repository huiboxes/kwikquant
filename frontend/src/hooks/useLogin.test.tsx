import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useLogin } from './useLogin'
import { useAuthStore } from '@/stores/authStore'
import { ApiError } from '@/lib/http'

// react-router useNavigate mock
const mockNavigate = vi.fn()
vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
}))

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return wrapper
}

// jsx 需求:vitest 配置 jsx(react-runtime automatic),默认 tsconfig jsx 已配
const React = await import('react')
void React

describe('useLogin', () => {
  beforeEach(() => {
    mockNavigate.mockClear()
    useAuthStore.getState().clearAuth()
    localStorage.clear()
  })

  it('成功:setAccessToken + navigate /', async () => {
    const { result } = renderHook(() => useLogin(), { wrapper: createWrapper() })
    await act(async () => {
      result.current.mutate({ username: 'alice', password: 'password123' })
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(useAuthStore.getState().status).toBe('authenticated')
    expect(useAuthStore.getState().user?.username).toBe('alice')
    expect(mockNavigate).toHaveBeenCalledWith('/')
  })

  it('失败(密码错误):不 setAccessToken,不 navigate', async () => {
    const { result } = renderHook(() => useLogin(), { wrapper: createWrapper() })
    await act(async () => {
      result.current.mutate({ username: 'alice', password: 'wrong' })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(useAuthStore.getState().status).toBe('anonymous')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(result.current.error).toBeInstanceOf(ApiError)
  })
})
