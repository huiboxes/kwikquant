import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { LoadingState } from '@/components/feedback/LoadingState'
import { AppLayout } from '@/components/layout/AppLayout'
import { LandingPage } from '@/pages/LandingPage'
import { loginUrlFor } from '@/lib/redirect'

/**
 * RootGuard — 首页路由守卫(公开 landing + 私有 app 分流，零子路由 path 破坏)。
 *
 * - unknown(启动探活)→ LoadingState
 * - anonymous 访问 / → 显 LandingPage(不跳 /login，让未登录用户看产品首页)
 * - anonymous 访问子路径(/strategy /trade 等)→ 跳 /login(记 from 便于回跳)
 * - authenticated → 渲染 AppLayout，其 <Outlet/> 承载 / 的 children(index DashboardPage、strategy、trade…)
 *
 * 设计动机：把公开 landing 放在 / 而不改动现有 /strategy /trade 等子路由 path,
 * 避免破坏 navItems 与全站导航链接。RequireAuth 仍守 /login /register 之外的 * 兜底。
 */
export function RootGuard() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'unknown') {
    return <LoadingState label="加载中…" />
  }
  if (status === 'anonymous') {
    if (location.pathname === '/') {
      return <LandingPage />
    }
    // from 用 query 传递(刷新不丢),登录页读取侧 sanitize 防开放重定向
    return <Navigate to={loginUrlFor(location)} replace />
  }
  return <AppLayout />
}
