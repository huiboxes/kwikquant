import type { ReactNode } from 'react'
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RegisterPage } from './RegisterPage'
import { useAuthStore } from '@/stores/authStore'

function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}

function ui(node: ReactNode, initial = '/register') {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[initial]}>{node}</MemoryRouter>
    </QueryClientProvider>,
  )
}

async function fillValid(user: ReturnType<typeof userEvent.setup>, invite = 'GOOD') {
  await user.type(screen.getByLabelText('用户名'), 'newuser')
  await user.type(screen.getByLabelText('邮箱'), 'n@e.com')
  await user.type(screen.getByLabelText('密码'), 'pass1234')
  await user.type(screen.getByLabelText('确认密码'), 'pass1234')
  await user.type(screen.getByLabelText('邀请码'), invite)
}

describe('RegisterPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clearAuth()
  })

  it('渲染 hero + 5 字段 + 创建账户按钮', () => {
    ui(<RegisterPage />)
    expect(screen.getByText(/写代码/)).toBeInTheDocument()
    for (const l of ['用户名', '邮箱', '密码', '确认密码', '邀请码']) {
      expect(screen.getByLabelText(l)).toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: /创建账户/ })).toBeInTheDocument()
  })

  it('密码不一致 → "两次密码不一致"', async () => {
    ui(<RegisterPage />)
    await userEvent.type(screen.getByLabelText('用户名'), 'newuser')
    await userEvent.type(screen.getByLabelText('邮箱'), 'n@e.com')
    await userEvent.type(screen.getByLabelText('密码'), 'pass1234')
    await userEvent.type(screen.getByLabelText('确认密码'), 'pass5678')
    await userEvent.type(screen.getByLabelText('邀请码'), 'GOOD')
    await userEvent.click(screen.getByRole('button', { name: /创建账户/ }))
    expect(await screen.findByText('两次密码不一致')).toBeInTheDocument()
  })

  it('邀请码空 → "请输入邀请码"', async () => {
    ui(<RegisterPage />)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('用户名'), 'newuser')
    await user.type(screen.getByLabelText('邮箱'), 'n@e.com')
    await user.type(screen.getByLabelText('密码'), 'pass1234')
    await user.type(screen.getByLabelText('确认密码'), 'pass1234')
    // 邀请码留空
    await user.click(screen.getByRole('button', { name: /创建账户/ }))
    expect(await screen.findByText('请输入邀请码')).toBeInTheDocument()
  })

  it('合法输入 → 注册成功 → 跳 /', async () => {
    ui(
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/" element={<div>HOME</div>} />
      </Routes>,
    )
    const user = userEvent.setup()
    await fillValid(user, 'GOOD')
    await user.click(screen.getByRole('button', { name: /创建账户/ }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  it('邀请码 BAD → "邀请码无效或已用尽"', async () => {
    ui(<RegisterPage />)
    const user = userEvent.setup()
    await fillValid(user, 'BAD')
    await user.click(screen.getByRole('button', { name: /创建账户/ }))
    expect(await screen.findByText('邀请码无效或已用尽')).toBeInTheDocument()
  })

  it('登录链接到 /login', () => {
    ui(<RegisterPage />)
    expect(screen.getByRole('link', { name: '登录' })).toHaveAttribute('href', '/login')
  })
})
