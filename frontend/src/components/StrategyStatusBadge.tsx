import { chipVariants } from '@/components/Chip'
import { StatusDot } from '@/components/StatusDot'
import { cn } from '@/lib/utils'

/**
 * StrategyStatusBadge — 策略状态徽章。
 * Chip 内嵌 StatusDot + 中文 label,4 态:running/paused/stopped/draft。
 * 用 chipVariants 直接套 span(Chip label 是 string,此处要嵌 StatusDot 所以直接用 cva)。
 */

export type StrategyStatus = 'running' | 'paused' | 'stopped' | 'draft'

const MAP: Record<StrategyStatus, { label: string; color: 'up' | 'warning' | 'neutral' | 'info' }> = {
  running: { label: '运行中', color: 'up' },
  paused: { label: '已暂停', color: 'warning' },
  stopped: { label: '已停止', color: 'neutral' },
  draft: { label: '草稿', color: 'info' },
}

export function StrategyStatusBadge({
  status,
  className,
}: {
  status: StrategyStatus | string
  className?: string
}) {
  const m = MAP[status as StrategyStatus] ?? { label: status, color: 'neutral' as const }
  return (
    <span className={cn(chipVariants({ color: m.color, size: 'sm' }), className)}>
      <StatusDot status={status} />
      {m.label}
    </span>
  )
}
