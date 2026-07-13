import { create } from 'zustand'

/**
 * notifStore — 通知状态(layout 数据接线)。
 *
 * 数据源:`/topic/notifications/{userId}` WS 推送 NotificationEvent(ws-contract §3.5,
 * type: RISK_REJECTED/ORDER_FILLED/ORDER_CANCELLED/STRATEGY_STARTED/STRATEGY_STOPPED/STRATEGY_ERROR)。
 *
 * honest(TD-053):WS STOMP 订阅未建(lib/ws subscribe 未实现),store 初始 mock 5 条占位。
 * WS 接后删 mock,改 addNotification 推送(WS 订阅层调 addNotification 转换 event→Notif)。
 * 风控 RiskEvent 走 notification 通道(type=RISK_REJECTED),不单独建模(ws-contract §3.5 注)。
 */
export type NotifType =
  | 'risk'
  | 'fill'
  | 'cancel'
  | 'strat_start'
  | 'strat_stopped'
  | 'strat_error'

export interface Notif {
  id: string
  type: NotifType
  title: string
  body: string
  ts: string
  unread: boolean
}

// mock 占位(TD-053:WS 未接,lib/ws STOMP subscribe 未建;接后删 mock)
const MOCK_NOTIFS: Notif[] = [
  { id: 'n1', type: 'risk', title: '风控拦截', body: '订单 o-9006 触发单笔限额,已被拒绝', ts: '2 分钟前', unread: true },
  { id: 'n2', type: 'fill', title: '订单成交', body: 'BTC/USDT BUY 0.42 @ 61200 已全部成交', ts: '5 分钟前', unread: true },
  { id: 'n3', type: 'cancel', title: '订单撤销', body: 'o-9004 已撤销', ts: '22 分钟前', unread: false },
  { id: 'n4', type: 'strat_start', title: '策略启动', body: 'BTC Trend Rider v1.3.2 已启动', ts: '1 小时前', unread: false },
  { id: 'n5', type: 'strat_error', title: '策略异常', body: 'Grid Scalper 撮合超时,已自动停止', ts: '昨天', unread: false },
]

interface NotifState {
  notifications: Notif[]
  /** WS 推送新增通知(置顶,默认 unread)。 */
  addNotification: (n: Notif) => void
  /** 全部标记已读。 */
  markAllRead: () => void
  /** 清空。 */
  clear: () => void
}

export const useNotifStore = create<NotifState>((set) => ({
  notifications: MOCK_NOTIFS,
  addNotification: (n) => set((s) => ({ notifications: [n, ...s.notifications] })),
  markAllRead: () =>
    set((s) => ({ notifications: s.notifications.map((n) => ({ ...n, unread: false })) })),
  clear: () => set({ notifications: [] }),
}))
