/* eslint-disable react-refresh/only-export-components -- 路由配置文件,非组件文件,react-refresh 不适用 */
import { lazy, Suspense } from 'react'
import type { RouteObject } from 'react-router-dom'
import { createBrowserRouter } from 'react-router-dom'
import { RequireAuth } from '@/components/RequireAuth'
import { AppLayout } from '@/components/layout/AppLayout'
import { EmptyState } from '@/components/EmptyState'
import { ErrorBoundary } from '@/components/ErrorBoundary'

const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const RegisterPage = lazy(() => import('./pages/RegisterPage').then((m) => ({ default: m.RegisterPage })))
const HistoryPage = lazy(() => import('./pages/HistoryPage').then((m) => ({ default: m.HistoryPage })))
const RiskPage = lazy(() => import('./pages/RiskPage').then((m) => ({ default: m.RiskPage })))
const PortfolioPage = lazy(() => import('./pages/PortfolioPage').then((m) => ({ default: m.PortfolioPage })))
const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const MarketPage = lazy(() => import('./pages/MarketPage').then((m) => ({ default: m.MarketPage })))
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((m) => ({ default: m.SettingsPage })))
const StrategyPage = lazy(() => import('./pages/StrategyPage').then((m) => ({ default: m.StrategyPage })))
const TradingPage = lazy(() => import('./pages/TradingPage').then((m) => ({ default: m.TradingPage })))

function PageFallback() {
  return <div className="flex h-full items-center justify-center text-text-muted">加载中…</div>
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
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: suspense(<DashboardPage />) },
      { path: 'strategy', element: suspense(<StrategyPage />) },
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
        <div className="flex min-h-screen items-center justify-center bg-surface-canvas p-xl">
          <EmptyState title="页面不存在" description="这个页面还没造出来。" />
        </div>
      </RequireAuth>
    ),
  },
]

export const router = createBrowserRouter(routes)
