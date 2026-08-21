import type { LucideIcon } from 'lucide-react'
import {
  Home,
  Code2,
  LayoutTemplate,
  Activity,
  Zap,
  Wallet,
  CandlestickChart,
  Shield,
  ArrowLeftRight,
  Settings,
} from 'lucide-react'

/**
 * Sidebar 导航项(数据驱动，照原型 AppLayout.jsx NAV 结构)。
 *
 * 两组：主线旅程(编码→回测→模拟→实盘)+ 监控与管理。
 * 每项 icon 用 lucide-react(不手抄原型 SVG path,D8 约定 #9)。
 * to = 真实路由 path；未实现页路由渲染"待实现"占位，不影响导航。
 */
export type NavGroup = '主线旅程' | '监控与管理'

export interface NavItem {
  id: string
  label: string
  /** 收起态显示的短标签 */
  short: string
  to: string
  icon: LucideIcon
  /** 副标题(展开态 label 下方) */
  sub: string
  group: NavGroup
}

export const NAV_GROUPS: NavGroup[] = ['主线旅程', '监控与管理']

export const NAV_ITEMS: NavItem[] = [
  { id: 'dashboard', label: '主页', short: '主页', to: '/', icon: Home, sub: '继续旅程', group: '主线旅程' },
  { id: 'strategy', label: '策略工作台', short: '策略', to: '/strategy', icon: Code2, sub: '写策略 · AI 搭把手', group: '主线旅程' },
  { id: 'templates', label: '模板库', short: '模板', to: '/templates', icon: LayoutTemplate, sub: '官方模板 · fork 即用', group: '主线旅程' },
  // 回测独立页(BacktestPage:跨策略全量回测记录 + 对比)；策略工作台内的回测 tab 只管当前策略。
  { id: 'backtest', label: '回测', short: '回测', to: '/backtest', icon: Activity, sub: '验证策略', group: '主线旅程' },
  { id: 'trade', label: '交易', short: '交易', to: '/trade', icon: Zap, sub: '模拟 / 实盘', group: '主线旅程' },
  { id: 'portfolio', label: '组合总览', short: '组合', to: '/portfolio', icon: Wallet, sub: '多账户汇总', group: '监控与管理' },
  { id: 'market', label: '行情', short: '行情', to: '/market', icon: CandlestickChart, sub: 'K 线 · 订单簿', group: '监控与管理' },
  { id: 'risk', label: '风控', short: '风控', to: '/risk', icon: Shield, sub: '保护你的每一笔下单', group: '监控与管理' },
  { id: 'history', label: '交易历史', short: '历史', to: '/history', icon: ArrowLeftRight, sub: '成交记录', group: '监控与管理' },
  { id: 'settings', label: '设置', short: '设置', to: '/settings', icon: Settings, sub: '账号与集成', group: '监控与管理' },
]

/** trade 项 id(用于 PAPER/LIVE badge 渲染) */
export const TRADE_NAV_ID = 'trade'
