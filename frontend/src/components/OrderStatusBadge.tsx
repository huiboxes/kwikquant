import { Chip } from '@/components/Chip'

/**
 * OrderStatusBadge — 订单状态徽章(纯 Chip,无 StatusDot)。
 * 9 态:NEW/PENDING/SUBMITTED/PARTIALLY_FILLED/FILLED/PENDING_CANCEL/CANCELED/REJECTED/EXPIRED。
 * 对齐原型 ui.jsx OrderStatusBadge 映射。
 */

export type OrderStatus =
  | 'NEW'
  | 'PENDING'
  | 'SUBMITTED'
  | 'PARTIALLY_FILLED'
  | 'FILLED'
  | 'PENDING_CANCEL'
  | 'CANCELED'
  | 'REJECTED'
  | 'EXPIRED'

const MAP: Record<OrderStatus, { label: string; color: 'info' | 'warning' | 'up' | 'neutral' | 'down' }> = {
  NEW: { label: '新建', color: 'info' },
  PENDING: { label: '待提交', color: 'warning' },
  SUBMITTED: { label: '已提交', color: 'info' },
  PARTIALLY_FILLED: { label: '部分成交', color: 'warning' },
  FILLED: { label: '全部成交', color: 'up' },
  PENDING_CANCEL: { label: '待撤销', color: 'warning' },
  CANCELED: { label: '已撤销', color: 'neutral' },
  REJECTED: { label: '被拒', color: 'down' },
  EXPIRED: { label: '过期', color: 'neutral' },
}

export function OrderStatusBadge({
  status,
  className,
}: {
  status: OrderStatus | string
  className?: string
}) {
  const m = MAP[status as OrderStatus] ?? { label: status, color: 'neutral' as const }
  return <Chip label={m.label} color={m.color} className={className} />
}
