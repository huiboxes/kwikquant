/* eslint-disable react-refresh/only-export-components -- 路由配置文件，非组件文件，react-refresh 不适用 */
import { lazy, Suspense } from 'react'
import type { RouteObject } from 'react-router-dom'
import { createBrowserRouter, useNavigate } from 'react-router-dom'
import { RequireAuth } from '@/components/RequireAuth'
import { RootGuard } from '@/components/RootGuard'
import { EmptyState } from '@/components/EmptyState'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { Button } from '@/components/ui/button'

const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const RegisterPage = lazy(() => import('./pages/RegisterPage').then((m) => ({ default: m.RegisterPage })))
const HistoryPage = lazy(() => import('./pages/HistoryPage').then((m) => ({ default: m.HistoryPage })))
const RiskPage = lazy(() => import('./pages/RiskPage').then((m) => ({ default: m.RiskPage })))
const PortfolioPage = lazy(() => import('./pages/PortfolioPage').then((m) => ({ default: m.PortfolioPage })))
const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const MarketPage = lazy(() => import('./pages/MarketPage').then((m) => ({ default: m.MarketPage })))
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((m) => ({ default: m.SettingsPage })))
const StrategyPage = lazy(() => import('./pages/StrategyPage').then((m) => ({ default: m.StrategyPage })))
const TemplatesPage = lazy(() => import('./pages/TemplatesPage').then((m) => ({ default: m.TemplatesPage })))
const BacktestPage = lazy(() => import('./pages/BacktestPage').then((m) => ({ default: m.BacktestPage })))
const TradingPage = lazy(() => import('./pages/TradingPage').then((m) => ({ default: m.TradingPage })))

function PageFallback() {
  return <div className="flex h-full items-center justify-center text-text-muted">加载中…</div>
}

/**
 * 404 兜底页。给「回首页」出路:登录回跳(?from=)可能把用户带回已失效/敲错的路径,
 * 裸空态无导航是死胡同(后退又回登录表单),行动按钮自愈。
 */
function NotFoundPage() {
  const navigate = useNavigate()
  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-canvas p-xl">
      <EmptyState
        title="页面不存在"
        description="这个页面还没造出来。"
        action={<Button onClick={() => navigate('/')}>回到首页</Button>}
      />
    </div>
  )
}

const suspense = (el: React.ReactNode) => (
  <ErrorBoundary>
    <Suspense fallback={<PageFallback />}>{el}</Suspense>
  </ErrorBoundary>
)

export const routes: RouteObject[] = [
  { path: '/login', element: suspense(<LoginPage />) },
  { path: '/register', element: suspense(<RegisterPage />) },
  {
    path: '/',
    element: <RootGuard />,
    children: [
      { index: true, element: suspense(<DashboardPage />) },
      { path: 'strategy', element: suspense(<StrategyPage />) },
      { path: 'templates', element: suspense(<TemplatesPage />) },
      { path: 'backtest', element: suspense(<BacktestPage />) },
      { path: 'trade', element: suspense(<TradingPage />) },
      { path: 'portfolio', element: suspense(<PortfolioPage />) },
      { path: 'market', element: suspense(<MarketPage />) },
      { path: 'risk', element: suspense(<RiskPage />) },
      { path: 'history', element: suspense(<HistoryPage />) },
      { path: 'settings', element: suspense(<SettingsPage />) },
    ],
  },
  {
    path: '*',
    element: (
      <RequireAuth>
        <NotFoundPage />
      </RequireAuth>
    ),
  },
]

export const router = createBrowserRouter(routes)
