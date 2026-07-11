import { useState } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { LogOut, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { NAV_GROUPS, NAV_ITEMS, TRADE_NAV_ID, type NavItem } from './navItems'
import { useUiStore } from '@/stores/uiStore'
import { useLogout } from '@/hooks/useLogout'
import { toDecimal, formatMoney, formatMoneyCompact } from '@/lib/money'
import { cn } from '@/lib/utils'

// mock 占位(真实运行中策略数 + 总资产后续接 strategy/account store)
const MOCK_RUNNING = 1
const MOCK_EQUITY = toDecimal('124556.99')

/**
 * SidebarRail — 左侧可折叠导航(照原型 Sidebar 重建)。
 * 桌面(collapsible=true):浮动折叠钮 + 分组 nav(active 渐变+发光左条)+ footer + 退出。
 * 移动(collapsible=false):AppLayout 用 Sheet(left) 包本组件,常展无折叠钮,onNavigate 关抽屉。
 * 无 border-r——靠 bg-surface-card vs 画布配色分隔(原型不用结构性边框)。
 * 折叠态 localStorage 持久化(key=kwikquant.sidebar.collapsed)。
 */
export function SidebarRail({
  collapsible = true,
  onNavigate,
  className,
}: {
  collapsible?: boolean
  onNavigate?: () => void
  className?: string
}) {
  const { pathname } = useLocation()
  const logout = useLogout()
  const tradeMode = useUiStore((s) => s.tradeMode)
  const [collapsed, setCollapsed] = useState<boolean>(
    () => localStorage.getItem('kwikquant.sidebar.collapsed') === 'true',
  )
  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    localStorage.setItem('kwikquant.sidebar.collapsed', String(next))
  }
  // 非折叠模式(移动抽屉)强制展开
  const effCollapsed = collapsible ? collapsed : false

  return (
    <aside
      className={cn(
        'relative flex flex-col bg-surface-card transition-[width] motion-base',
        className,
        effCollapsed ? 'w-[64px]' : 'w-[248px]',
      )}
      aria-label="主导航"
    >
      {/* 浮动折叠钮——仅桌面 collapsible 模式 */}
      {collapsible && (
        <button
          type="button"
          onClick={toggle}
          aria-label={effCollapsed ? '展开侧栏' : '收起侧栏'}
          className="absolute right-[-11px] top-1/2 z-10 flex h-[24px] w-[24px] -translate-y-1/2 items-center justify-center rounded-full bg-surface-card text-text-secondary shadow-card transition-colors motion-fast hover:bg-accent-soft hover:text-accent"
        >
          {effCollapsed ? <ChevronsRight className="h-[16px] w-[16px]" /> : <ChevronsLeft className="h-[16px] w-[16px]" />}
        </button>
      )}

      {/* 品牌 */}
      <div className={cn('flex items-center gap-xs', effCollapsed ? 'justify-center py-lg' : 'px-md py-lg')}>
        <div className="flex h-[28px] w-[28px] items-center justify-center rounded-lg bg-accent-soft font-mono text-caption text-accent" aria-hidden>
          KQ
        </div>
        {!effCollapsed && (
          <div className="leading-tight">
            <div className="text-body font-semibold text-text-primary">KwikQuant</div>
            <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">AI Native Quant</div>
          </div>
        )}
      </div>

      {/* nav 区(分组) */}
      <nav className={cn('kq-sidebar-scroll flex-1 min-h-0 overflow-y-auto overflow-x-hidden py-xs', effCollapsed ? 'px-0' : 'px-sm')}>
        {NAV_GROUPS.map((group, gidx) => (
          <div key={group} className={cn(gidx > 0 && 'mt-md')}>
            {!effCollapsed && <div className="px-xs pb-xs text-label-caps text-text-muted">{group}</div>}
            <div className={cn('flex flex-col', effCollapsed ? 'gap-sm' : 'gap-xxs')}>
              {NAV_ITEMS.filter((it) => it.group === group).map((item) => (
                <NavButton key={item.id} item={item} pathname={pathname} collapsed={effCollapsed} tradeMode={tradeMode} onNavigate={onNavigate} />
              ))}
            </div>
          </div>
        ))}
      </nav>

      {/* footer:运行中 + 总资产(mock) */}
      {effCollapsed ? (
        <div className="flex flex-col items-center gap-sm px-0 py-md">
          <div className="flex flex-col items-center">
            <span className="text-label-caps text-text-muted">RUN</span>
            <span className="text-body font-bold text-up">{MOCK_RUNNING}</span>
          </div>
          <div className="flex flex-col items-center">
            <span className="text-label-caps text-text-muted">EQ</span>
            <span className="font-mono-num text-caption font-bold">${formatMoneyCompact(MOCK_EQUITY)}</span>
          </div>
        </div>
      ) : (
        <div className="mx-sm mb-sm rounded-lg bg-surface-card-2 p-md">
          <div className="flex items-center justify-between">
            <span className="text-caption text-text-muted">运行中策略</span>
            <span className="text-body font-bold text-up">{MOCK_RUNNING}</span>
          </div>
          <div className="mt-xs flex items-center justify-between">
            <span className="text-caption text-text-muted">总资产</span>
            <span className="font-mono-num text-body font-bold">$ {formatMoney(MOCK_EQUITY, { dp: 2 })}</span>
          </div>
        </div>
      )}

      {/* 退出 */}
      {effCollapsed ? (
        <button
          type="button"
          onClick={() => { logout(); onNavigate?.() }}
          aria-label="退出登录"
          className="mx-auto mb-md flex h-[40px] w-[40px] items-center justify-center rounded-md text-text-secondary transition-colors hover:bg-surface-card-2 hover:text-text-primary"
        >
          <LogOut className="h-[20px] w-[20px]" />
          <span className="sr-only">退出登录</span>
        </button>
      ) : (
        <div className="px-sm pb-sm">
          <button
            type="button"
            onClick={() => { logout(); onNavigate?.() }}
            aria-label="退出登录"
            className="flex h-[40px] w-full items-center justify-center gap-xs rounded-md text-text-secondary transition-colors hover:bg-surface-card-2 hover:text-text-primary"
          >
            <LogOut className="h-[20px] w-[20px]" />
            <span className="text-body-sm">退出登录</span>
          </button>
        </div>
      )}
    </aside>
  )
}

function NavButton({
  item,
  pathname,
  collapsed,
  tradeMode,
  onNavigate,
}: {
  item: NavItem
  pathname: string
  collapsed: boolean
  tradeMode: 'PAPER' | 'LIVE'
  onNavigate?: () => void
}) {
  const Icon = item.icon
  const isActive = item.to === '/' ? pathname === '/' : pathname.startsWith(item.to)
  const isTrade = item.id === TRADE_NAV_ID

  // kq-nav-item 提供 padding/rounded/color/hover/active 渐变+发光左条(单一样式源)
  const base = cn(
    'kq-nav-item',
    isActive && 'active',
    collapsed && 'flex-col h-[56px] w-full items-center justify-center gap-xxs',
  )

  return (
    <NavLink to={item.to} end={item.to === '/'} aria-label={collapsed ? item.label : undefined} className={base} onClick={() => onNavigate?.()}>
      <Icon className="h-[18px] w-[18px] shrink-0" />
      {collapsed ? (
        <span className="text-label-caps leading-none text-inherit">{item.short}</span>
      ) : (
        <span className="flex flex-1 flex-col leading-tight">
          <span className="flex items-center gap-xs text-body font-medium">
            {item.label}
            {isTrade &&
              (tradeMode === 'LIVE' ? (
                <span className="rounded bg-accent px-[6px] py-xxs text-label-caps text-on-accent">LIVE</span>
              ) : (
                <span className="rounded border border-border bg-surface-card-2 px-[6px] py-xxs text-label-caps text-text-muted">
                  PAPER
                </span>
              ))}
          </span>
          <span className="text-caption text-text-muted">
            {isTrade ? (tradeMode === 'LIVE' ? 'LIVE 实盘' : 'PAPER 模拟') : item.sub}
          </span>
        </span>
      )}
      {/* 折叠态 trade 彩点(brand/up,LIVE 发光) */}
      {collapsed && isTrade && (
        <span className={cn('absolute right-[2px] top-[4px] h-[6px] w-[6px] rounded-full', tradeMode === 'LIVE' ? 'bg-accent shadow-[0_0_6px_var(--accent)]' : 'bg-up')} />
      )}
    </NavLink>
  )
}
