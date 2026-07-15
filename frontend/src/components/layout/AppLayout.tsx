import { Outlet, useLocation } from 'react-router-dom'
import { useEffect } from 'react'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { SidebarRail } from './SidebarRail'
import { TopBar } from './TopBar'
import { CommandMenu } from './CommandMenu'
import { NotifDrawer } from './NotifDrawer'
import { useUiStore } from '@/stores/uiStore'
import { getWsConnection } from '@/lib/ws/ConnectionManager'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { useAuth } from '@/hooks/useAuth'
import { useNotifStore, eventToNotif } from '@/stores/notifStore'
import { useTradingEvents } from '@/hooks/useTradingEvents'

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

  // 登录后(AppLayout 仅 authenticated 渲染)启动 WS 连接。
  // cookie 认证(refresh_token path=/),connect 幂等,刷新 remount 安全。
  useEffect(() => {
    getWsConnection().connect()
  }, [])

  // 订阅通知 WS(/topic/notifications/{userId}),收到 NotificationEvent 转 Notif 入 store。
  const { user } = useAuth()
  const notifTopic = user ? `/topic/notifications/${user.userId}` : null
  const addNotification = useNotifStore((s) => s.addNotification)
  useWsTopic(notifTopic, (payload) => {
    addNotification(eventToNotif(payload as { type: string; timestamp: string }))
  })

  // 全局订阅 trading 主题(order/fill/position),事件 invalidate 对应 queryKeys,各页自动刷新(WS-6)
  useTradingEvents(user?.userId ?? null)

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
