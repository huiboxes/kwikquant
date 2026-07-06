/**
 * Sidebar 导航项(数据驱动,spec §2.2 共 6 项,无 Risk)。
 *
 * active 项可点击跳转;comingSoon 项 disabled,显示 "即将推出" tooltip。
 * 启用 disabled 项时只改 active 字段,不改结构。
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
  { label: '总览', to: '/', active: true },
  { label: '策略工作台', to: '/workbench', active: true },
  { label: '投资组合', to: '/portfolio', active: true },
  { label: '行情', to: '/markets', active: false, comingSoonNote: '批 3 启用' },
  { label: '交易历史', to: '/trades', active: false, comingSoonNote: '批 4 启用' },
  { label: '设置', to: '/settings', active: false, comingSoonNote: '批 5 启用' },
]
