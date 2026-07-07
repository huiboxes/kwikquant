/**
 * Sidebar 导航项(数据驱动)。
 *
 * 重做 IA 时填入导航项;当前清空(旧 IA 已删,避免污染重做)。
 * active 项可点击跳转;active=false 项 disabled,显示 "即将推出" tooltip。
 */
export interface NavItem {
  label: string
  to: string
  /** true = 可点击跳转;false = disabled 占位(coming-soon) */
  active: boolean
  /** disabled 项的说明文案 */
  comingSoonNote?: string
}

// 重做 IA 时填入。当前清空,SidebarRail 渲染空 nav(结构在)。
export const NAV_ITEMS: NavItem[] = []
