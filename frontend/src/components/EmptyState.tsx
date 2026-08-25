import type { ReactNode } from 'react'

/**
 * EmptyState — 空状态占位。
 *
 * 居中插图(emoji/icon)+ 描述文字 + 行动按钮。
 * 场景：无账户/无订单/无持仓/无策略/无回测结果。
 *
 * DESIGN.md token: surface-card 底 + text-muted 插图 + text-primary 标题 + text-secondary 描述。
 */
export interface EmptyStateProps {
  /** 插图(emoji 或 ReactNode icon) */
  illustration?: ReactNode
  /** 标题，如"还没有策略" */
  title: string
  /** 描述，如"创建第一个策略开始量化交易" */
  description?: string
  /** 行动按钮(由调用方传 <Button>) */
  action?: ReactNode
  /** 表格行内等紧凑场景用：收窄留白，避免空表撑出大白板 */
  compact?: boolean
}

export function EmptyState({ illustration, title, description, action, compact = false }: EmptyStateProps) {
  return (
    <div
      className={`flex flex-col items-center justify-center gap-md rounded-lg bg-surface-card text-center ${
        compact ? 'min-h-[120px] p-lg' : 'min-h-[240px] p-2xl'
      }`}
      role="status"
      aria-live="polite"
    >
      {illustration && (
        <div className="text-text-muted" aria-hidden>
          {illustration}
        </div>
      )}
      <p className="font-body text-body text-text-primary">{title}</p>
      {description && (
        <p className="max-w-sm font-body text-body-sm text-text-secondary">{description}</p>
      )}
      {action}
    </div>
  )
}
