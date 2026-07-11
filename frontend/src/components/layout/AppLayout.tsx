import { Outlet, useLocation } from 'react-router-dom'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { SidebarRail } from './SidebarRail'
import { TopBar } from './TopBar'
import { CommandMenu } from './CommandMenu'
import { NotifDrawer } from './NotifDrawer'
import { useUiStore } from '@/stores/uiStore'

/**
 * AppLayout — 登录后主骨架(照原型 AppLayout 重建,含移动端响应式)。
 *
 * 桌面(≥900px):侧栏(kq-desktop-nav)+ 内容列(TopBar sticky + main 滚动)。
 * 移动(<900px):侧栏隐藏,TopBar hamburger 开 Sheet(left) 抽屉式 nav(collapsible=false,onNavigate 关)。
 * main max-w-[1400px] 居中 + padding,子页 <Outlet/> 包 kq-page-enter(key=pathname 进场动画)。
 * 挂 NotifDrawer + CommandMenu(全局浮层,开关态在 uiStore)。
 */
export function AppLayout() {
  const { pathname } = useLocation()
  const mobileNavOpen = useUiStore((s) => s.mobileNavOpen)
  const setMobileNavOpen = useUiStore((s) => s.setMobileNavOpen)

  return (
    <div className="flex h-dvh bg-surface-canvas text-text-primary">
      {/* 桌面侧栏(移动 <900px 隐藏 via kq-desktop-nav) */}
      <SidebarRail className="kq-desktop-nav" />

      {/* 移动端 nav 抽屉 */}
      <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
        <SheetContent side="left" className="w-[248px] max-w-[85vw] gap-0 border-transparent bg-surface-card p-0 shadow-pop">
          <SidebarRail collapsible={false} onNavigate={() => setMobileNavOpen(false)} />
        </SheetContent>
      </Sheet>

      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="min-h-0 flex-1 overflow-y-auto py-[28px] px-xl">
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
