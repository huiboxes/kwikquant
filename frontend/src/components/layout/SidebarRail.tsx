import { NavLink, useLocation } from 'react-router-dom'
import { Home, Code2, Wallet, CandlestickChart, ArrowLeftRight, Shield, Settings } from 'lucide-react'
import { NAV_ITEMS, type NavItem } from './navItems'
import { ThemeToggle } from '@/components/ThemeToggle'

const ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  Home,
  Strategies: Code2,
  Portfolio: Wallet,
  Markets: CandlestickChart,
  Trades: ArrowLeftRight,
  Risk: Shield,
  Settings,
}

/**
 * SidebarRail — 左侧窄竖条导航(spec §5 共享基建 #6)。
 *
 * 数据驱动(NAV_ITEMS):active 项 NavLink 可点跳转,disabled 项 span 灰显 + title tooltip。
 * 批 2-5 启用 disabled 项时只改 navItems.ts 的 active 字段,本组件不返工。
 *
 * DESIGN.md token: bg-surface-card / border-border / text-text-* / rounded。
 * 激活态用 accent token(暖铜),非激活用 text-secondary,disabled 用 text-muted + interactive-disabled。
 */
export function SidebarRail() {
  const { pathname } = useLocation()
  return (
    <aside
      className="flex w-[72px] flex-col items-center gap-md border-r border-border bg-surface-card py-lg"
      aria-label="主导航"
    >
      {/* Brand mark — 暖铜圆点(DESIGN.md accent) */}
      <div
        className="flex h-[40px] w-[40px] items-center justify-center rounded-full bg-primary"
        aria-hidden
      >
        <span className="h-[10px] w-[10px] rounded-full bg-accent" />
      </div>

      <nav className="flex flex-1 flex-col items-center gap-sm">
        {NAV_ITEMS.map((item) => (
          <NavButton key={item.label} item={item} pathname={pathname} />
        ))}
      </nav>

      {/* 主题切换 — 底部,与品牌标记(顶部)对称 */}
      <ThemeToggle />
    </aside>
  )
}

function NavButton({ item, pathname }: { item: NavItem; pathname: string }) {
  const Icon = ICONS[item.label] ?? Home
  const isActive = item.active && pathname.startsWith(item.to) && item.to !== '/'
  // Home 单独匹配根路径
  const isHomeActive = item.to === '/' && pathname === '/'

  if (!item.active) {
    return (
      <span
        className="flex h-[44px] w-[44px] items-center justify-center rounded-full text-text-muted/60"
        title={`${item.label}（${item.comingSoonNote ?? '即将推出'}）`}
        aria-disabled="true"
      >
        <Icon className="h-[20px] w-[20px]" />
      </span>
    )
  }

  return (
    <NavLink
      to={item.to}
      end={item.to === '/'}
      className={({ isActive: linkActive }) => {
        const active = linkActive || isActive || isHomeActive
        return [
          'flex h-[44px] w-[44px] items-center justify-center rounded-full transition-colors',
          active
            ? 'bg-primary text-accent'
            : 'text-text-secondary hover:bg-surface-hover hover:text-text-primary',
        ].join(' ')
      }}
    >
      <Icon className="h-[20px] w-[20px]" />
      <span className="sr-only">{item.label}</span>
    </NavLink>
  )
}
