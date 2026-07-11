/* eslint-disable react-refresh/only-export-components -- 路由配置文件,非组件文件,react-refresh 不适用 */
import { lazy, Suspense } from 'react'
import type { RouteObject } from 'react-router-dom'
import { createBrowserRouter } from 'react-router-dom'
import { RequireAuth } from '@/components/RequireAuth'
import { AppLayout } from '@/components/layout/AppLayout'
import { EmptyState } from '@/components/EmptyState'

const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const RegisterPage = lazy(() => import('./pages/RegisterPage').then((m) => ({ default: m.RegisterPage })))

/** 待实现页占位(8 个业务页照原型移植留后续 spec) */
function Placeholder({ title }: { title: string }) {
  return <EmptyState title={`${title} · 待实现`} description="本页将在后续 spec 照原型移植。" />
}

function PageFallback() {
  return <div className="flex h-full items-center justify-center text-text-muted">加载中…</div>
}

const suspense = (el: React.ReactNode) => <Suspense fallback={<PageFallback />}>{el}</Suspense>

export const routes: RouteObject[] = [
  { path: '/login', element: suspense(<LoginPage />) },
  { path: '/register', element: suspense(<RegisterPage />) },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Placeholder title="主页" /> },
      { path: 'strategy', element: <Placeholder title="策略工作台" /> },
      { path: 'backtest', element: <Placeholder title="回测" /> },
      { path: 'trade', element: <Placeholder title="交易" /> },
      { path: 'portfolio', element: <Placeholder title="组合总览" /> },
      { path: 'market', element: <Placeholder title="行情" /> },
      { path: 'risk', element: <Placeholder title="风控" /> },
      { path: 'history', element: <Placeholder title="交易历史" /> },
      { path: 'settings', element: <Placeholder title="设置" /> },
    ],
  },
  {
    path: '*',
    element: (
      <RequireAuth>
        <div className="flex min-h-screen items-center justify-center bg-surface-canvas p-xl">
          <EmptyState title="页面不存在" description="这个页面还没造出来。" />
        </div>
      </RequireAuth>
    ),
  },
]

export const router = createBrowserRouter(routes)
