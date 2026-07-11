import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routes } from './routes'
import { useAuthStore } from '@/stores/authStore'
import { useUiStore } from '@/stores/uiStore'

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

function authed() {
  useAuthStore.setState({ status: 'authenticated', user: { userId: 1, username: 'demo' }, accessToken: 'dummy' })
}

describe('routes', () => {
  beforeEach(() => {
    useAuthStore.getState().clearAuth()
    useUiStore.setState({ cmdOpen: false, notifOpen: false, tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })

  it('/login 渲染 LoginPage(进入工作台按钮)', async () => {
    renderAt('/login')
    expect(await screen.findByRole('button', { name: /进入工作台/ })).toBeInTheDocument()
  })

  it('/register 渲染 RegisterPage(创建账户按钮)', async () => {
    renderAt('/register')
    expect(await screen.findByRole('button', { name: /创建账户/ })).toBeInTheDocument()
  })

  it('/ 未认证 → 跳 /login', async () => {
    renderAt('/')
    expect(await screen.findByRole('button', { name: /进入工作台/ })).toBeInTheDocument()
  })

  it('/ 已认证 → AppLayout + 主页占位', async () => {
    authed()
    renderAt('/')
    expect(await screen.findByText('主页 · 待实现')).toBeInTheDocument()
    expect(screen.getByRole('banner')).toBeInTheDocument() // TopBar
  })

  it('/strategy 已认证 → 占位 + 面包屑页名', async () => {
    authed()
    renderAt('/strategy')
    expect(await screen.findByText('策略工作台 · 待实现')).toBeInTheDocument()
    // '策略工作台' 在侧栏 nav + 顶栏面包屑都出现(多个)
    expect(screen.getAllByText('策略工作台').length).toBeGreaterThan(0)
  })

  it('/nonexistent 已认证 → 404 页', async () => {
    authed()
    renderAt('/nonexistent')
    expect(await screen.findByText('页面不存在')).toBeInTheDocument()
  })
})
