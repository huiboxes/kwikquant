/* eslint-disable react-refresh/only-export-components -- 路由配置文件,非组件文件,react-refresh 不适用 */
import { createBrowserRouter } from 'react-router-dom'
import { SidebarRail } from '@/components/layout/SidebarRail'
import { TopBar } from '@/components/layout/TopBar'
import { RequireAuth } from '@/components/RequireAuth'

/**
 * 占位组件 — 业务页面重做前的临时渲染。重做时移除,改为真实页面 lazy import。
 * 用 DESIGN.md token,不引入硬编码样式。
 */
function Placeholder({ title }: { title: string }) {
  return (
    <div className="flex h-full items-center justify-center bg-surface-canvas">
      <span className="font-body text-body-sm text-text-secondary">{title} · 待重做</span>
    </div>
  )
}

/**
 * AppShell — TopBar + 侧边栏 + 主内容区(布局壳,重做时保留)。
 */
function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-screen flex-col bg-surface-canvas text-text-primary">
      <TopBar />
      <div className="flex flex-1 min-h-0">
        <SidebarRail />
        <main className="flex-1 min-w-0">{children}</main>
      </div>
    </div>
  )
}

const withShell = (element: React.ReactNode) => (
  <RequireAuth>
    <AppShell>{element}</AppShell>
  </RequireAuth>
)

export const router = createBrowserRouter([
  { path: '/login', element: <Placeholder title="登录" /> },
  { path: '/register', element: <Placeholder title="注册" /> },
  { path: '/', element: withShell(<Placeholder title="总览" />) },
  { path: '*', element: withShell(<Placeholder title="页面不存在" />) },
])
