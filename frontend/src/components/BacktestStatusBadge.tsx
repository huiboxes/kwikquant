import { chipVariants } from '@/components/Chip'
import { StatusDot } from '@/components/StatusDot'
import { cn } from '@/lib/utils'

/**
 * BacktestStatusBadge — 回测状态徽章。
 * Chip 内嵌 StatusDot + 中文 label,4 态:COMPLETED/RUNNING/FAILED/PENDING。
 */

export type BacktestStatus = 'COMPLETED' | 'RUNNING' | 'FAILED' | 'PENDING'

const MAP: Record<BacktestStatus, { label: string; color: 'up' | 'accent' | 'down' | 'warning' }> = {
  COMPLETED: { label: '已完成', color: 'up' },
  RUNNING: { label: '运行中', color: 'accent' },
  FAILED: { label: '失败', color: 'down' },
  PENDING: { label: '待处理', color: 'warning' },
}

export function BacktestStatusBadge({
  status,
  className,
}: {
  status: BacktestStatus | string
  className?: string
}) {
  const m = MAP[status as BacktestStatus] ?? { label: status, color: 'neutral' as const }
  return (
    <span className={cn(chipVariants({ color: m.color, size: 'sm' }), className)}>
      <StatusDot status={status} />
      {m.label}
    </span>
  )
}
