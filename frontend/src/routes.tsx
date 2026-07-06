/* eslint-disable react-refresh/only-export-components -- 路由配置文件,非组件文件,react-refresh 不适用 */
import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { SidebarRail } from '@/components/layout/SidebarRail'
import { RequireAuth } from '@/components/RequireAuth'
import { LoadingState } from '@/components/feedback/LoadingState'

// 路由级懒加载(Monaco 等重组件不进主 bundle)
const Dashboard = lazy(() => import('@/pages/Dashboard').then((m) => ({ default: m.Dashboard })))
const Login = lazy(() => import('@/pages/Login').then((m) => ({ default: m.Login })))
const Register = lazy(() => import('@/pages/Register').then((m) => ({ default: m.Register })))
const StrategyNew = lazy(() => import('@/pages/StrategyNew').then((m) => ({ default: m.StrategyNew })))
const Accounts = lazy(() => import('@/pages/Accounts').then((m) => ({ default: m.Accounts })))
const StrategyWorkbench = lazy(() =>
  import('@/pages/StrategyWorkbench').then((m) => ({ default: m.StrategyWorkbench })),
)
const NotFound = lazy(() => import('@/pages/NotFound').then((m) => ({ default: m.NotFound })))

/**
 * AppShell — 侧边栏 + 主内容区。
 * 认证页(/login /register)不走 shell(裸布局);其余走 shell。
 */
function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen bg-surface-canvas text-text-primary">
      <SidebarRail />
      <main className="flex-1 min-w-0">{children}</main>
    </div>
  )
}

const withShell = (element: React.ReactNode) => (
  <RequireAuth>
    <AppShell>{element}</AppShell>
  </RequireAuth>
)

export const router = createBrowserRouter([
  { path: '/login', element: <Suspense fallback={<LoadingState />}><Login /></Suspense> },
  { path: '/register', element: <Suspense fallback={<LoadingState />}><Register /></Suspense> },
  { path: '/', element: withShell(<Suspense fallback={<LoadingState />}><Dashboard /></Suspense>) },
  {
    path: '/strategies/new',
    element: withShell(<Suspense fallback={<LoadingState />}><StrategyNew /></Suspense>),
  },
  {
    path: '/portfolio',
    element: withShell(<Suspense fallback={<LoadingState />}><Accounts /></Suspense>),
  },
  {
    path: '/strategies/:id',
    element: withShell(<Suspense fallback={<LoadingState />}><StrategyWorkbench /></Suspense>),
  },
  // /strategies 列表重定向到首页舰队(Dashboard 即策略舰队,无独立列表页)
  { path: '/strategies', element: <Navigate to="/" replace /> },
  { path: '*', element: withShell(<Suspense fallback={<LoadingState />}><NotFound /></Suspense>) },
])
