import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { LoadingState } from '@/components/feedback/LoadingState'

/**
 * RequireAuth — 路由守卫(spec §5 step 2)。
 *
 * unknown(启动探活) → LoadingState;anonymous → 跳 /login(记 from 便于登录后回跳);
 * authenticated → 渲染 children。
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'unknown') {
    return <LoadingState label="认证中…" />
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />
  }
  return <>{children}</>
}
