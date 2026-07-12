import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routes } from './routes'
import { useAuthStore } from '@/stores/authStore'
import { useUiStore } from '@/stores/uiStore'

// Monaco 在 jsdom 不可用(canvas/WebWorker/CDN loader),mock 掉(StrategyPage 经 routes 懒加载时需要)
vi.mock('@monaco-editor/react', () => ({
  default: ({ defaultValue }: { defaultValue?: string }) => (
    <textarea data-testid="monaco-mock" defaultValue={defaultValue ?? ''} />
  ),
}))

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

  it('/ 已认证 → AppLayout(侧栏 + TopBar)渲染', async () => {
    authed()
    renderAt('/')
    // 验证路由壳(AppLayout 渲染,未跳 /login);DashboardPage 内容由 dashboard-page.test 测
    expect(await screen.findByRole('banner')).toBeInTheDocument() // TopBar
    expect(screen.getByRole('complementary', { name: /主导航/ })).toBeInTheDocument() // SidebarRail
  })

  it('/strategy 已认证 → StrategyPage 渲染 + 面包屑页名', async () => {
    authed()
    renderAt('/strategy')
    // StrategyPage 已接线(非占位),header 跑回测按钮渲染(lazy chunk + MSW 查询慢,放宽 timeout)
    expect(await screen.findByRole('button', { name: /跑回测/ }, { timeout: 5000 })).toBeInTheDocument()
    // '策略工作台' 在侧栏 nav + 顶栏面包屑都出现(多个)
    expect(screen.getAllByText('策略工作台').length).toBeGreaterThan(0)
  })

  it('/nonexistent 已认证 → 404 页', async () => {
    authed()
    renderAt('/nonexistent')
    expect(await screen.findByText('页面不存在')).toBeInTheDocument()
  })
})
