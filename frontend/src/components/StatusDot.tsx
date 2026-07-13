import { cn } from '@/lib/utils'

/**
 * StatusDot — 状态点(dot)。
 * 套 index.css 既有的 .kq-status-dot(尺寸/形状)+ .kq-status-dot-live(脉冲环动画),
 * 颜色按 status 映射脚手架 CSS 变量(--up/--warning/--text-muted/--info/--accent/--down),inline 染色 + glow boxShadow。
 * 用于策略/回测/订单状态徽章前置点、运行中脉冲指示。
 */
const STATUS_COLOR: Record<string, string> = {
  // 策略状态
  running: 'var(--up)',
  paused: 'var(--warning)',
  stopped: 'var(--text-muted)',
  draft: 'var(--info)',
  // 回测状态
  COMPLETED: 'var(--up)',
  RUNNING: 'var(--accent)',
  FAILED: 'var(--down)',
  PENDING: 'var(--warning)',
}

export function StatusDot({
  status,
  className,
}: {
  status: string
  className?: string
}) {
  const color = STATUS_COLOR[status] ?? 'var(--text-muted)'
  const live = status === 'running' || status === 'RUNNING'
  return (
    <span
      className={cn('kq-status-dot', live && 'kq-status-dot-live', className)}
      style={{ background: color, boxShadow: `0 0 8px ${color}`, color }}
      aria-hidden
    />
  )
}
