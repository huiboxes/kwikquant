import { create } from 'zustand'

/**
 * notifStore — 通知状态(layout 数据接线)。
 *
 * 数据源:`/topic/notifications/{userId}` WS 推送 NotificationEvent(ws-contract §3.7)。
 * AppLayout 订阅 WS,handler 调 eventToNotif 转换 + addNotification。
 * 风控 RiskEvent 走 notification 通道(type=RISK_REJECTED),不单独建模。
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

/** NotificationEvent(WS §3.7) → Notif(store)。title 按 type 派生(event 无 title 字段)。 */
export function eventToNotif(ev: {
  type: string
  timestamp: string
  orderId?: number
  reason?: string
  [k: string]: unknown
}): Notif {
  const map: Record<string, { type: NotifType; title: string }> = {
    RISK_REJECTED: { type: 'risk', title: '风控拦截' },
    ORDER_FILLED: { type: 'fill', title: '订单成交' },
    ORDER_CANCELLED: { type: 'cancel', title: '订单撤销' },
    STRATEGY_STARTED: { type: 'strat_start', title: '策略启动' },
    STRATEGY_STOPPED: { type: 'strat_stopped', title: '策略停止' },
    STRATEGY_ERROR: { type: 'strat_error', title: '策略异常' },
  }
  const m = map[ev.type] ?? { type: 'risk' as NotifType, title: '通知' }
  // body 按 type 拼(event payload 字段不统一,orderId/reason 常见)
  const body =
    ev.type === 'RISK_REJECTED'
      ? `订单 ${ev.orderId ?? '-'} ${ev.reason ?? '触发风控'}`
      : ev.orderId != null
        ? `订单 ${ev.orderId}`
        : m.title
  return {
    id: `${ev.type}-${ev.timestamp}`,
    type: m.type,
    title: m.title,
    body,
    ts: ev.timestamp,
    unread: true,
  }
}

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
  notifications: [],
  addNotification: (n) => set((s) => ({ notifications: [n, ...s.notifications] })),
  markAllRead: () =>
    set((s) => ({ notifications: s.notifications.map((n) => ({ ...n, unread: false })) })),
  clear: () => set({ notifications: [] }),
}))
