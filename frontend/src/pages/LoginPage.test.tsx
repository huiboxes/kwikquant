import type { ReactNode } from 'react'
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { LoginPage } from './LoginPage'
import { useAuthStore } from '@/stores/authStore'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}

function ui(node: ReactNode, initial = '/login') {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[initial]}>{node}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clearAuth()
  })

  it('渲染品牌 hero + 表单(用户名/密码/进入工作台)', () => {
    const { container } = ui(<LoginPage />)
    expect(screen.getByText(/写策略，做回测/)).toBeInTheDocument()
    expect(screen.getByLabelText('用户名')).toBeInTheDocument()
    expect(screen.getByLabelText('密码')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /进入工作台/ })).toBeInTheDocument()
    expect(container.firstElementChild).toHaveClass('overflow-x-hidden')
    expect(screen.getByLabelText('用户名').closest('form')?.parentElement).toHaveClass('min-w-0')
  })

  it('空提交显 zod 错(请输入用户名/密码)', async () => {
    ui(<LoginPage />)
    await userEvent.click(screen.getByRole('button', { name: /进入工作台/ }))
    expect(await screen.findByText('请输入用户名')).toBeInTheDocument()
    expect(screen.getByText('请输入密码')).toBeInTheDocument()
  })

  it('合法凭证 → 登录成功 → 跳 /', async () => {
    ui(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<div>HOME</div>} />
      </Routes>,
    )
    await userEvent.type(screen.getByLabelText('用户名'), 'demo')
    await userEvent.type(screen.getByLabelText('密码'), 'pass1234')
    await userEvent.click(screen.getByRole('button', { name: /进入工作台/ }))
    // msw login handler 返 token → setAccessToken(decodeJwt sub 字段)→ navigate('/')
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  it('密码错 → 显"用户名或密码错误"(inline)', async () => {
    ui(<LoginPage />)
    await userEvent.type(screen.getByLabelText('用户名'), 'demo')
    await userEvent.type(screen.getByLabelText('密码'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: /进入工作台/ }))
    expect(await screen.findByText('用户名或密码错误')).toBeInTheDocument()
  })

  it('服务异常时不展示后端内部错误原文', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json(envelope(null, 5001, 'database connection refused'), { status: 500 }),
      ),
    )
    ui(<LoginPage />)
    await userEvent.type(screen.getByLabelText('用户名'), 'demo')
    await userEvent.type(screen.getByLabelText('密码'), 'pass1234')
    await userEvent.click(screen.getByRole('button', { name: /进入工作台/ }))
    expect(await screen.findByText('登录服务暂时不可用，请稍后再试')).toBeInTheDocument()
    expect(screen.queryByText(/database connection refused/)).not.toBeInTheDocument()
  })

  it('注册链接到 /register', () => {
    ui(<LoginPage />)
    expect(screen.getByRole('link', { name: '注册' })).toHaveAttribute('href', '/register')
  })

  it('?from= 深链 → 登录成功回跳原页面', async () => {
    ui(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/trade" element={<div>TRADE</div>} />
        <Route path="/" element={<div>HOME</div>} />
      </Routes>,
      '/login?from=%2Ftrade',
    )
    await userEvent.type(screen.getByLabelText('用户名'), 'demo')
    await userEvent.type(screen.getByLabelText('密码'), 'pass1234')
    await userEvent.click(screen.getByRole('button', { name: /进入工作台/ }))
    expect(await screen.findByText('TRADE')).toBeInTheDocument()
  })

  it('?from= 指向站外(protocol-relative)→ 忽略并兜底 /', async () => {
    ui(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<div>HOME</div>} />
      </Routes>,
      '/login?from=%2F%2Fevil.com',
    )
    await userEvent.type(screen.getByLabelText('用户名'), 'demo')
    await userEvent.type(screen.getByLabelText('密码'), 'pass1234')
    await userEvent.click(screen.getByRole('button', { name: /进入工作台/ }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  it('?from= 存在时注册链接透传回跳目标', () => {
    ui(<LoginPage />, '/login?from=%2Fportfolio')
    expect(screen.getByRole('link', { name: '注册' })).toHaveAttribute(
      'href',
      '/register?from=%2Fportfolio',
    )
  })
})
