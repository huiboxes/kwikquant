import type { ReactNode } from 'react'
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useSearchParams } from 'react-router-dom'
import { RequireAuth } from './RequireAuth'
import { RootGuard } from './RootGuard'
import { useAuthStore } from '@/stores/authStore'

/** /login 探针:显示守卫写入的 from query(解码后),验证写侧契约 */
function LoginProbe() {
  const [searchParams] = useSearchParams()
  return <div>LOGIN from={searchParams.get('from') ?? '(none)'}</div>
}

function guardRoutes(inner: ReactNode, initial: string) {
  return render(
    <MemoryRouter initialEntries={[initial]}>
      <Routes>
        <Route path="/login" element={<LoginProbe />} />
        <Route path="/" element={inner}>
          <Route path="strategy" element={<div>STRATEGY</div>} />
          <Route path="trade" element={<div>TRADE</div>} />
          <Route path="portfolio" element={<div>PORTFOLIO</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('守卫写侧 from 契约(RequireAuth/RootGuard)', () => {
  beforeEach(() => {
    useAuthStore.getState().clearAuth() // → anonymous
  })

  it('RequireAuth: anonymous 访问受保护路径 → /login?from= 带完整深链(含 search)', () => {
    guardRoutes(
      <RequireAuth>
        <div>CHILD</div>
      </RequireAuth>,
      '/strategy?taskId=3&retry=1',
    )
    // 深链参数编码进 from,登录后无损回跳
    expect(screen.getByText('LOGIN from=/strategy?taskId=3&retry=1')).toBeInTheDocument()
  })

  it('RootGuard: anonymous 访问子路径 → /login?from=', () => {
    guardRoutes(<RootGuard />, '/portfolio')
    expect(screen.getByText('LOGIN from=/portfolio')).toBeInTheDocument()
  })

  it('RootGuard: anonymous 访问 / → 显 LandingPage 不跳登录', () => {
    guardRoutes(<RootGuard />, '/')
    expect(screen.queryByText(/^LOGIN from=/)).not.toBeInTheDocument()
  })

  it('RequireAuth: authenticated → 渲染 children 不跳转', () => {
    useAuthStore.setState({ status: 'authenticated' })
    guardRoutes(
      <RequireAuth>
        <div>CHILD</div>
      </RequireAuth>,
      '/trade',
    )
    expect(screen.getByText('CHILD')).toBeInTheDocument()
  })
})
