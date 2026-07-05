import { Link } from 'react-router-dom'
import { cva, type VariantProps } from 'class-variance-authority'
import { clsx } from 'clsx'
import { ChevronRight } from 'lucide-react'
import type { StrategyDetailDto } from '@/hooks/useStrategies'
import { Chip } from './Chip'

/**
 * StrategyCard — 策略卡片(spec §5 step 11,DESIGN.md §card Airbnb 卡片风)。
 *
 * 卡片展示:名称 + 交易对 + 交易所 + 状态徽章 + PnL/持仓/风控 stub(批 2 补真实数据)。
 * 点击跳工作区 /strategies/:id?stage=code。
 *
 * DESIGN.md token:surface-card 底 + rounded-xl + shadow-card + hover shadow-card-hover。
 */
const strategyCardVariants = cva(
  'group flex flex-col gap-md rounded-xl bg-surface-card p-lg shadow-card transition-all hover:shadow-card-hover',
)

type StrategyStatus = string

function statusToChipColor(status: StrategyStatus): 'up' | 'down' | 'warning' | 'info' | 'neutral' | 'accent' {
  switch (status) {
    case 'RUNNING':
      return 'up'
    case 'STOPPED':
    case 'FAILED':
      return 'down'
    case 'PUBLISHED':
      return 'accent'
    case 'PAUSED':
      return 'warning'
    case 'DRAFT':
    default:
      return 'neutral'
  }
}

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  STOPPED: '已停止',
  FAILED: '失败',
}

export interface StrategyCardProps
  extends React.ComponentProps<'article'>,
    VariantProps<typeof strategyCardVariants> {
  strategy: StrategyDetailDto
}

export function StrategyCard({ strategy, className, ...props }: StrategyCardProps) {
  const status = (strategy as { status?: string }).status ?? 'DRAFT'
  return (
    <Link to={`/strategies/${strategy.id}?stage=code`} className="block">
      <article className={clsx(strategyCardVariants(), className)} {...props}>
        <div className="flex items-start justify-between">
          <div className="space-y-sm">
            <h3 className="font-display text-h3">{strategy.name}</h3>
            <p className="font-body text-body-sm text-text-secondary">
              {strategy.symbol} · {strategy.exchange}
            </p>
          </div>
          <Chip label={STATUS_LABEL[status] ?? status} color={statusToChipColor(status)} />
        </div>

        {strategy.description && (
          <p className="line-clamp-2 font-body text-body-sm text-text-muted">
            {strategy.description}
          </p>
        )}

        {/* stub 指标行(批 2 接真实持仓/PnL/风控) */}
        <div className="mt-auto flex items-center justify-between border-t border-border-soft pt-md">
          <span className="font-mono-num text-caption text-text-muted">
            PnL · 持仓 · 风控 待接入
          </span>
          <ChevronRight className="size-4 text-text-muted transition-transform group-hover:translate-x-1" />
        </div>
      </article>
    </Link>
  )
}
