/**
 * Sidebar 导航项(数据驱动,spec §5 共享基建 #6)。
 *
 * active 项可点击跳转;comingSoon 项 disabled,显示 "即将推出" tooltip。
 * 批 2-5 启用 portfolio/markets/trades/risk/settings 时只改 comingSoon: false,不改结构(不返工)。
 */
export interface NavItem {
  label: string
  to: string
  /** true = 可点击跳转;false = disabled 占位(coming-soon) */
  active: boolean
  /** disabled 项的说明文案 */
  comingSoonNote?: string
}

export const NAV_ITEMS: NavItem[] = [
  { label: 'Home', to: '/', active: true },
  { label: 'Strategies', to: '/strategies/new', active: true },
  { label: 'Portfolio', to: '/portfolio', active: false, comingSoonNote: '批 2 启用' },
  { label: 'Markets', to: '/markets', active: false, comingSoonNote: '批 3 启用' },
  { label: 'Trades', to: '/trades', active: false, comingSoonNote: '批 4 启用' },
  { label: 'Risk', to: '/risk', active: false, comingSoonNote: '批 4 启用' },
  { label: 'Settings', to: '/settings', active: false, comingSoonNote: '批 5 启用' },
]
