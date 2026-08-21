import { useLocation, useNavigate } from 'react-router-dom'
import { Search, Bell, Menu } from 'lucide-react'
import { NAV_ITEMS } from './navItems'
import { useUiStore } from '@/stores/uiStore'
import { useAuth } from '@/hooks/useAuth'
import { ThemeToggle } from '@/components/ThemeToggle'
import { WsConnectionIndicator } from '@/components/WsConnectionIndicator'
import { TradeModeToggle } from '@/components/TradeModeToggle'
import { useNotifStore } from '@/stores/notifStore'

function pageName(pathname: string): string {
  if (pathname === '/') return '主页'
  const hit = NAV_ITEMS.find((it) => it.to !== '/' && pathname.startsWith(it.to))
  return hit?.label ?? '页面不存在'
}

/**
 * TopBar — 60px 顶栏(照原型 Topbar 重建)。
 * 无 border-b——靠 sticky + backdrop-blur + 半透画布分隔(原型不用结构性边框)。
 * 左:hamburger(kq-hamburger，移动端显示)+ 面包屑；右：搜索(→命令面板)+ 主题 + 通知(→抽屉)+ 账户(→/settings)+ WS。
 */
export function TopBar() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const { user } = useAuth()
  const setCmdOpen = useUiStore((s) => s.setCmdOpen)
  const setNotifOpen = useUiStore((s) => s.setNotifOpen)
  const setMobileNavOpen = useUiStore((s) => s.setMobileNavOpen)
  const tradeMode = useUiStore((s) => s.tradeMode)
  const unread = useNotifStore((s) => s.notifications.filter((n) => n.unread).length)

  const account = user?.username ?? 'demo'

  return (
    <header className="sticky top-0 z-20 flex h-[60px] items-center justify-between gap-sm bg-surface-canvas/80 px-base backdrop-blur-md md:px-lg">
      {/* <980px 面包屑折叠为当前页名(DESIGN.md collapsing,「返回」待补)，前缀宽屏显示。
          搜索框 <1280px 折 icon:桌面右簇实测 ~706px(含 280px 框),980–1280 带宽扣除
          248px 侧栏后放不下搜索框+面包屑，1280+(Wide 带)恒够宽 */}
      <div className="flex min-w-0 items-center gap-sm">
        {/* 移动端 hamburger(桌面隐藏，kq-hamburger <900px 显) */}
        <button
          type="button"
          onClick={() => setMobileNavOpen(true)}
          aria-label="打开导航"
          className="kq-hamburger h-[36px] w-[36px] shrink-0 items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary"
        >
          <Menu className="h-[18px] w-[18px]" />
        </button>
        <span className="hidden text-body-sm text-text-muted min-[980px]:inline">KwikQuant</span>
        <span className="hidden text-text-muted min-[980px]:inline">/</span>
        <span className="truncate text-body font-semibold text-text-primary">{pageName(pathname)}</span>
      </div>

      <div className="flex shrink-0 items-center gap-xxs md:gap-xs">
        {/* 搜索触发器 → 命令面板(桌面完整框) */}
        <button
          type="button"
          onClick={() => setCmdOpen(true)}
          aria-label="打开命令面板"
          className="hidden h-[36px] w-[280px] items-center gap-xs rounded-md border border-border bg-surface-card-2 px-md text-text-muted transition-colors motion-fast hover:bg-surface-hover min-[1280px]:flex"
        >
          <Search className="h-[14px] w-[14px]" />
          <span className="flex-1 text-left text-body-sm">搜索策略 / 标的 / 页面 / 命令…</span>
          <kbd className="rounded border border-border bg-surface-card px-xxs font-mono text-label-caps">⌘K</kbd>
        </button>
        {/* 移动端搜索 icon 按钮(同开命令面板，accessible name 与桌面一致) */}
        <button
          type="button"
          onClick={() => setCmdOpen(true)}
          aria-label="打开命令面板"
          className="flex h-[36px] w-[36px] items-center justify-center rounded-full text-text-secondary transition-colors motion-fast hover:bg-surface-hover hover:text-text-primary min-[1280px]:hidden"
        >
          <Search className="h-[18px] w-[18px]" />
        </button>

        <TradeModeToggle />

        <ThemeToggle />

        {/* 通知 */}
        <button
          type="button"
          onClick={() => setNotifOpen(true)}
          aria-label="通知"
          className="relative flex h-[36px] w-[36px] items-center justify-center rounded-full text-text-secondary transition-colors motion-fast hover:bg-surface-hover hover:text-text-primary"
        >
          <Bell className="h-[18px] w-[18px]" />
          {unread > 0 && (
            <span className="absolute right-1 top-1 flex h-[16px] min-w-[16px] items-center justify-center rounded-full bg-accent px-xxs text-label-caps text-on-accent">
              {unread}
            </span>
          )}
        </button>

        {/* 账户 chip → /settings */}
        <button
          type="button"
          onClick={() => navigate('/settings?tab=accounts')}
          aria-label="账户设置"
          className="flex items-center gap-xs rounded-lg bg-surface-card-2 px-sm py-xxs max-sm:px-xxs transition-colors motion-fast hover:bg-surface-hover"
        >
          <span className="flex h-[24px] w-[24px] items-center justify-center rounded-full bg-accent text-label-caps text-on-accent">
            {account.charAt(0).toUpperCase()}
          </span>
          <span className="hidden leading-tight sm:block">
            <span className="block text-caption font-semibold text-text-primary">{account}</span>
            <span className="block text-caption text-text-muted">
              {tradeMode === 'PAPER' ? '模拟盘' : '实盘'}
            </span>
          </span>
        </button>

        <WsConnectionIndicator />
      </div>
    </header>
  )
}
