import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useRegister } from './useRegister'
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

const React = await import('react')
void React

describe('useRegister', () => {
  beforeEach(() => {
    mockNavigate.mockClear()
    useAuthStore.getState().clearAuth()
    localStorage.clear()
  })

  it('成功(有效邀请码 KWIK-DEV-001):setAccessToken + navigate /', async () => {
    const { result } = renderHook(() => useRegister(), { wrapper: createWrapper() })
    await act(async () => {
      result.current.mutate({
        username: 'newuser',
        email: 'newuser@test.com',
        password: 'password123',
        confirmPassword: 'password123',
        inviteCode: 'KWIK-DEV-001',
      })
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(useAuthStore.getState().status).toBe('authenticated')
    expect(mockNavigate).toHaveBeenCalledWith('/')
  })

  it('失败(无效邀请码 400/3002):不 setAccessToken,不 navigate,error 是 ApiError(3002)', async () => {
    const { result } = renderHook(() => useRegister(), { wrapper: createWrapper() })
    await act(async () => {
      result.current.mutate({
        username: 'newuser',
        email: 'newuser@test.com',
        password: 'password123',
        confirmPassword: 'password123',
        inviteCode: 'INVALID-CODE',
      })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(useAuthStore.getState().status).toBe('anonymous')
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).code).toBe(3002)
  })
})
