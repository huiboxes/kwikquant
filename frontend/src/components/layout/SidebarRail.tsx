import { useState } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import {
  Home,
  Code2,
  Wallet,
  CandlestickChart,
  ArrowLeftRight,
  Settings,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
} from 'lucide-react'
import { NAV_ITEMS, type NavItem } from './navItems'
import { ThemeToggle } from '@/components/ThemeToggle'
import { useLogout } from '@/hooks/useLogout'
import { cn } from '@/lib/utils'

const ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  总览: Home,
  策略工作台: Code2,
  投资组合: Wallet,
  行情: CandlestickChart,
  交易历史: ArrowLeftRight,
  设置: Settings,
}

/**
 * SidebarRail — 左侧可折叠导航(spec §2.2,6 项无 Risk)。
 *
 * 折叠:展开 ~200px(图标+文字),收起 56px(图标 only)。localStorage 持久化。
 * 数据驱动(NAV_ITEMS):active 项 NavLink 可点,disabled 项 span 灰显 + title。
 *
 * DESIGN.md token: bg-surface-card / border-border / text-text-* / bg-primary(激活)。
 */
export function SidebarRail() {
  const { pathname } = useLocation()
  const logout = useLogout()
  const [collapsed, setCollapsed] = useState<boolean>(
    () => localStorage.getItem('kwikquant.sidebar.collapsed') === 'true',
  )
  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    localStorage.setItem('kwikquant.sidebar.collapsed', String(next))
  }

  return (
    <aside
      className={cn(
        'flex flex-col items-center border-r border-border bg-surface-card py-lg transition-[width]',
        collapsed ? 'w-[56px]' : 'w-[200px]',
      )}
      aria-label="主导航"
    >
      {/* Brand mark — 暖铜圆点(DESIGN.md accent) */}
      <div
        className="flex h-[40px] w-[40px] items-center justify-center rounded-full bg-primary"
        aria-hidden
      >
        <span className="h-[10px] w-[10px] rounded-full bg-accent" />
      </div>

      <nav
        className={cn(
          'flex flex-1 flex-col gap-sm',
          collapsed ? 'items-center' : 'w-full px-sm',
        )}
      >
        {NAV_ITEMS.map((item) => (
          <NavButton
            key={item.label}
            item={item}
            pathname={pathname}
            collapsed={collapsed}
          />
        ))}
      </nav>

      {/* 主题切换 */}
      <ThemeToggle />
      {/* 退出登录 */}
      <button
        type="button"
        onClick={logout}
        aria-label="退出登录"
        className="flex h-[44px] w-[44px] items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary"
      >
        <LogOut className="h-[20px] w-[20px]" />
        <span className="sr-only">退出登录</span>
      </button>
      {/* 折叠/展开切换 */}
      <button
        type="button"
        onClick={toggle}
        aria-label={collapsed ? '展开' : '收起'}
        className="flex h-[44px] w-[44px] items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary"
      >
        {collapsed ? (
          <PanelLeftOpen className="h-[20px] w-[20px]" />
        ) : (
          <PanelLeftClose className="h-[20px] w-[20px]" />
        )}
        <span className="sr-only">{collapsed ? '展开' : '收起'}</span>
      </button>
    </aside>
  )
}

function NavButton({
  item,
  pathname,
  collapsed,
}: {
  item: NavItem
  pathname: string
  collapsed: boolean
}) {
  const Icon = ICONS[item.label] ?? Home
  const isActive = item.active && pathname.startsWith(item.to) && item.to !== '/'
  // Home 单独匹配根路径
  const isHomeActive = item.to === '/' && pathname === '/'

  const baseClass = cn(
    'flex h-[44px] items-center rounded-full transition-colors',
    collapsed ? 'w-[44px] justify-center' : 'w-full gap-sm px-md',
  )

  if (!item.active) {
    return (
      <span
        className={cn(baseClass, 'text-text-muted/60')}
        title={`${item.label}（${item.comingSoonNote ?? '即将推出'}）`}
        aria-disabled="true"
        aria-label={collapsed ? item.label : undefined}
      >
        <Icon className="h-[20px] w-[20px]" />
        {!collapsed && <span>{item.label}</span>}
      </span>
    )
  }

  return (
    <NavLink
      to={item.to}
      end={item.to === '/'}
      aria-label={collapsed ? item.label : undefined}
      className={({ isActive: linkActive }) => {
        const active = linkActive || isActive || isHomeActive
        return cn(
          baseClass,
          active
            ? 'bg-primary text-accent'
            : 'text-text-secondary hover:bg-surface-hover hover:text-text-primary',
        )
      }}
    >
      <Icon className="h-[20px] w-[20px]" />
      {!collapsed && <span>{item.label}</span>}
    </NavLink>
  )
}
