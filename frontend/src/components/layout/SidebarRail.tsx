import { useState } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { LogOut, PanelLeftClose, PanelLeftOpen } from 'lucide-react'
import { NAV_GROUPS, NAV_ITEMS, TRADE_NAV_ID, type NavItem } from './navItems'
import { useUiStore } from '@/stores/uiStore'
import { useLogout } from '@/hooks/useLogout'
import { cn } from '@/lib/utils'

// mock 占位(真实运行中策略数 + 总资产后续接 strategy/account store)
const MOCK_RUNNING = 1
const MOCK_EQUITY = '$ 124,556.99'

/**
 * SidebarRail — 左侧可折叠导航(照原型 AppLayout.jsx Sidebar 重建)。
 *
 * 暗主默认暖黑侧栏。结构:品牌 mark + 浮动折叠钮 + 分组 nav(active 左条 + 软底)
 * + footer(运行中/总资产 mock)+ 底部退出。trade 项显 PAPER/LIVE badge(强区分)。
 * 折叠态 localStorage 持久化(key=kwikquant.sidebar.collapsed)。
 */
export function SidebarRail() {
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

  return (
    <aside
      className={cn(
        'relative flex flex-col border-r border-border bg-surface-card transition-[width] motion-base',
        collapsed ? 'w-[64px]' : 'w-[248px]',
      )}
      aria-label="主导航"
    >
      {/* 浮动折叠钮——侧栏右边缘垂直居中 */}
      <button
        type="button"
        onClick={toggle}
        aria-label={collapsed ? '展开侧栏' : '收起侧栏'}
        className="absolute right-[-11px] top-1/2 z-10 flex h-[22px] w-[22px] -translate-y-1/2 items-center justify-center rounded-full bg-surface-card text-text-secondary shadow-card transition-colors motion-fast hover:bg-accent-soft hover:text-accent"
      >
        {collapsed ? <PanelLeftOpen className="h-[14px] w-[14px]" /> : <PanelLeftClose className="h-[14px] w-[14px]" />}
      </button>

      {/* 品牌 */}
      <div
        className={cn(
          'flex items-center gap-xs',
          collapsed ? 'justify-center py-lg' : 'px-md py-lg',
        )}
      >
        <div
          className="flex h-[28px] w-[28px] items-center justify-center rounded-lg bg-accent-soft font-mono text-caption text-accent"
          aria-hidden
        >
          KQ
        </div>
        {!collapsed && (
          <div className="leading-tight">
            <div className="text-body font-semibold text-text-primary">KwikQuant</div>
            <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">AI Native Quant</div>
          </div>
        )}
      </div>

      {/* nav 区(分组) */}
      <nav
        className={cn(
          'flex-1 min-h-0 overflow-y-auto overflow-x-hidden py-xs',
          collapsed ? 'px-0' : 'px-sm',
        )}
      >
        {NAV_GROUPS.map((group, gidx) => (
          <div key={group} className={cn(gidx > 0 && 'mt-md')}>
            {!collapsed && (
              <div className="px-xs pb-xs text-label-caps text-text-muted">{group}</div>
            )}
            <div className={cn('flex flex-col', collapsed ? 'gap-sm' : 'gap-xxs')}>
              {NAV_ITEMS.filter((it) => it.group === group).map((item) => (
                <NavButton
                  key={item.id}
                  item={item}
                  pathname={pathname}
                  collapsed={collapsed}
                  tradeMode={tradeMode}
                />
              ))}
            </div>
          </div>
        ))}
      </nav>

      {/* footer:运行中 + 总资产(mock) */}
      {collapsed ? (
        <div className="flex flex-col items-center gap-sm px-0 py-md">
          <div className="flex flex-col items-center">
            <span className="text-label-caps text-text-muted">RUN</span>
            <span className="text-body font-bold text-up">{MOCK_RUNNING}</span>
          </div>
          <div className="flex flex-col items-center">
            <span className="text-label-caps text-text-muted">EQ</span>
            <span className="font-mono-num text-caption font-bold">{MOCK_EQUITY.replace('$ ', '')}</span>
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
            <span className="font-mono-num text-body font-bold">{MOCK_EQUITY}</span>
          </div>
        </div>
      )}

      {/* 退出 */}
      <button
        type="button"
        onClick={logout}
        aria-label="退出登录"
        className={cn(
          'flex items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary',
          collapsed ? 'mx-auto mb-md h-[40px] w-[40px]' : 'm-sm h-[40px] w-full gap-xs',
        )}
      >
        <LogOut className="h-[20px] w-[20px]" />
        {!collapsed && <span className="text-body-sm">退出登录</span>}
        <span className="sr-only">退出登录</span>
      </button>
    </aside>
  )
}

function NavButton({
  item,
  pathname,
  collapsed,
  tradeMode,
}: {
  item: NavItem
  pathname: string
  collapsed: boolean
  tradeMode: 'PAPER' | 'LIVE'
}) {
  const Icon = item.icon
  const isActive =
    item.to === '/' ? pathname === '/' : pathname.startsWith(item.to)
  const isTrade = item.id === TRADE_NAV_ID

  const base = cn(
    'relative flex items-center rounded-md transition-colors motion-fast',
    collapsed ? 'flex-col h-[56px] w-full items-center justify-center gap-xxs' : 'h-[44px] w-full gap-md px-md',
    isActive
      ? 'bg-accent-soft text-accent before:absolute before:left-0 before:top-2 before:bottom-2 before:w-0.5 before:rounded-r before:bg-accent before:content-[""]'
      : 'text-text-secondary hover:bg-surface-hover hover:text-text-primary',
  )

  return (
    <NavLink to={item.to} end={item.to === '/'} aria-label={collapsed ? item.label : undefined} className={base}>
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
    </NavLink>
  )
}
