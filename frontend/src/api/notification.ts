import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * notification typed client(通知偏好矩阵;SettingsPage notif tab 用)。
 *
 * 端点(均 JWT):
 *  - GET /api/v1/notifications/preferences  → List NotificationPreferenceDto(user 维度)
 *  - PUT /api/v1/notifications/preferences   body NotificationPreferenceRequest{preferences:PreferenceItem[]}
 *           → List NotificationPreferenceDto(幂等 upsert)
 *
 * honest:
 *  - eventType 枚举 6 个:RISK_REJECTED|ORDER_FILLED|ORDER_CANCELLED|STRATEGY_STARTED|
 *    STRATEGY_STOPPED|STRATEGY_ERROR(对上原型 EVENT_TYPES)。
 *  - channelType 枚举 WEBSOCKET|EMAIL 等(契约"等"模糊)。原型 4 渠道 ws/email/telegram/webhook。
 *    telegram/webhook 后端支持性未知 → UI 保留 4 渠道照原型,PUT 只传 WEBSOCKET/EMAIL(后端
 *    明确支持的);telegram/webhook 保持 UI 态不 PUT TD-028。
 *  - "无记录 = 默认推送,关闭 = 不推"(原型 sub 文案)。GET 返回的偏好是"已显式设置"项,
 *    未返回的 (eventType,channelType) 组合走默认(def 矩阵)。
 */
type NotificationPreferenceDto = components['schemas']['NotificationPreferenceDto']
type NotificationPreferenceRequest = components['schemas']['NotificationPreferenceRequest']
type PreferenceItem = components['schemas']['PreferenceItem']

export type { NotificationPreferenceDto, NotificationPreferenceRequest, PreferenceItem }

/** 通知 eventType 枚举(契约 api-gen;6 个,对上原型 EVENT_TYPES)。 */
export const NOTIF_EVENT_TYPES = [
  'RISK_REJECTED',
  'ORDER_FILLED',
  'ORDER_CANCELLED',
  'STRATEGY_STARTED',
  'STRATEGY_STOPPED',
  'STRATEGY_ERROR',
] as const
export type NotifEventType = (typeof NOTIF_EVENT_TYPES)[number]

/** 通知 channelType 枚举(契约明确 WEBSOCKET|EMAIL;telegram/webhook 后端支持性未知 TD-028)。 */
export const NOTIF_CHANNEL_TYPES = ['WEBSOCKET', 'EMAIL', 'TELEGRAM', 'WEBHOOK'] as const
export type NotifChannelType = (typeof NOTIF_CHANNEL_TYPES)[number]

/** eventType → 中文 label(原型 EVENT_TYPES.label)。 */
export function eventTypeLabel(t: string): string {
  switch (t) {
    case 'RISK_REJECTED':
      return '风控拒绝'
    case 'ORDER_FILLED':
      return '订单成交'
    case 'ORDER_CANCELLED':
      return '订单撤销'
    case 'STRATEGY_STARTED':
      return '策略启动'
    case 'STRATEGY_STOPPED':
      return '策略停止'
    case 'STRATEGY_ERROR':
      return '策略异常'
    default:
      return t
  }
}

/** channelType → 中文 label(原型 CHANNELS.label)。 */
export function channelTypeLabel(t: string): string {
  switch (t) {
    case 'WEBSOCKET':
      return '站内'
    case 'EMAIL':
      return '邮件'
    case 'TELEGRAM':
      return 'Telegram'
    case 'WEBHOOK':
      return 'Webhook'
    default:
      return t
  }
}

/** 查通知偏好列表(GET;返已显式设置的偏好项,未返回组合走默认)。 */
export function fetchNotifPrefs(): Promise<NotificationPreferenceDto[]> {
  return apiFetch<NotificationPreferenceDto[]>('/api/v1/notifications/preferences')
}

/** 批量更新通知偏好(PUT,幂等 upsert;返更新后偏好列表)。notif tab checkbox onChange 用。 */
export function upsertNotifPrefs(
  req: NotificationPreferenceRequest,
): Promise<NotificationPreferenceDto[]> {
  return apiFetch<NotificationPreferenceDto[]>('/api/v1/notifications/preferences', {
    method: 'PUT',
    body: req,
  })
}
