import { create } from 'zustand'

/**
 * 通知 store(spec §5 step 9)。
 *
 * 订阅 /topic/notifications/{userId},WS 推送 NotificationEvent 入此 store。
 * 未读计数 unreadCount + 列表 notifications(最新在前,≤100 条)。
 *
 * NotificationEvent 后端 fixture(WebSocketNotificationChannel.java 实际):
 *   {"type":"RISK_REJECTED","orderId":1,"accountId":1,"reason":"exceeds max notional","timestamp":"2026-07-05T12:00:00Z"}
 *   type 枚举:RISK_REJECTED | ORDER_FILLED | ORDER_CANCELLED | STRATEGY_STARTED | STRATEGY_STOPPED | STRATEGY_ERROR
 *   无 id/title(契约 F 已对齐 ws-contract §3.7)。
 */

export type NotificationType =
  | 'RISK_REJECTED'
  | 'ORDER_FILLED'
  | 'ORDER_CANCELLED'
  | 'STRATEGY_STARTED'
  | 'STRATEGY_STOPPED'
  | 'STRATEGY_ERROR'

export interface NotificationEvent {
  type: NotificationType
  orderId?: number
  accountId?: number
  strategyId?: number
  reason?: string
  message?: string
  timestamp: string
}

interface NotificationsState {
  notifications: NotificationEvent[]
  unreadCount: number
  push: (event: NotificationEvent) => void
  markAllRead: () => void
  clear: () => void
}

const MAX_NOTIFICATIONS = 100

export const useNotificationsStore = create<NotificationsState>((set) => ({
  notifications: [],
  unreadCount: 0,

  push: (event) =>
    set((s) => ({
      notifications: [event, ...s.notifications].slice(0, MAX_NOTIFICATIONS),
      unreadCount: s.unreadCount + 1,
    })),

  markAllRead: () => set({ unreadCount: 0 }),
  clear: () => set({ notifications: [], unreadCount: 0 }),
}))
