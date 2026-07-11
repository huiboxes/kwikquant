import { Outlet, useLocation } from 'react-router-dom'
import { SidebarRail } from './SidebarRail'
import { TopBar } from './TopBar'
import { CommandMenu } from './CommandMenu'
import { NotifDrawer } from './NotifDrawer'

/**
 * AppLayout — 登录后主骨架(照原型 AppLayout.jsx AppLayout 重建)。
 *
 * flex h-screen:侧栏(左,stretch 全高)+ 内容列(flex-1:TopBar sticky + main 滚动)。
 * main max-w-[1400px] 居中 + padding。子页通过 <Outlet/> 渲染(react-router 嵌套路由),
 * 包 kq-page-enter(key=pathname,导航时重触发进场动画)。
 * 挂 NotifDrawer + CommandMenu(全局浮层,开关态在 uiStore)。
 */
export function AppLayout() {
  const { pathname } = useLocation()
  return (
    <div className="flex h-screen bg-surface-canvas text-text-primary">
      <SidebarRail />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="min-h-0 flex-1 overflow-y-auto p-xl">
          <div className="mx-auto max-w-[1400px]">
            <div key={pathname} className="kq-page-enter">
              <Outlet />
            </div>
          </div>
        </main>
      </div>
      <NotifDrawer />
      <CommandMenu />
    </div>
  )
}
