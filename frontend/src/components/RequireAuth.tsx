import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { LoadingState } from '@/components/feedback/LoadingState'
import { loginUrlFor } from '@/lib/redirect'

/**
 * RequireAuth — 路由守卫。
 *
 * unknown(启动探活) → LoadingState;anonymous → 跳 /login?from=…(登录/注册成功后回跳原页面,
 * 用 query 而非 router state:刷新/整页跳转不丢,读取侧 sanitize 防开放重定向);
 * authenticated → 渲染 children。
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'unknown') {
    return <LoadingState label="认证中…" />
  }
  if (status === 'anonymous') {
    return <Navigate to={loginUrlFor(location)} replace />
  }
  return <>{children}</>
}
