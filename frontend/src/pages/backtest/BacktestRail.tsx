import { Chip } from '@/components/Chip'
import { Checkbox } from '@/components/ui/checkbox'
import { Progress } from '@/components/ui/progress'
import { toDecimal } from '@/lib/money'
import type { BacktestTaskDto } from '@/api/backtest'

function fmtPct(v: number | null | undefined, sign = true): string {
  if (v == null) return '—'
  const d = toDecimal(v).times(100)
  return `${sign && d.gte(0) ? '+' : ''}${d.toFixed(2)}%`
}

function statusLabel(status: BacktestTaskDto['status']): string {
  switch (status) {
    case 'COMPLETED':
      return '已完成'
    case 'RUNNING':
      return '运行中'
    case 'PENDING':
      return '排队中'
    case 'FAILED':
      return '失败'
    default:
      return status
  }
}

/** 单个回测卡片(照原型 BacktestPage.jsx 110-146 port；砍 Sparkline 用真实收益率)。
 * compareChecked/onToggleCompare 非空时(COMPLETED 且 reportId)右上角显对比勾选框。 */
export function BacktestCard({
  bt,
  selected,
  onClick,
  compareChecked,
  onToggleCompare,
}: {
  bt: BacktestTaskDto
  selected: boolean
  onClick: () => void
  compareChecked?: boolean
  onToggleCompare?: () => void
}) {
  const up = bt.totalReturn != null && toDecimal(bt.totalReturn).gte(0)
  const pct = bt.totalBars ? Math.round(((bt.processedBars ?? 0) / bt.totalBars) * 100) : 0
  const comparable = onToggleCompare != null
  return (
    <div
      data-selected={selected}
      onClick={onClick}
      aria-current={selected ? 'true' : undefined}
      className={`relative flex flex-[0_0_240px] flex-col gap-xxs rounded-lg border p-sm cursor-pointer transition-all ${selected ? 'border-accent bg-accent-soft/50 shadow-glow' : 'border-border-soft bg-surface-card hover:border-border hover:bg-surface-card-2'}`}
    >
      {selected && (
        <span
          aria-hidden
          className="absolute left-0 top-xs bottom-xs w-[2px] rounded-r-2 bg-accent shadow-glow"
        />
      )}
      {comparable && (
        <span
          className="absolute right-xs top-xs"
          onClick={(e) => e.stopPropagation()}
          title="勾选以加入对比"
        >
          <Checkbox checked={compareChecked} onCheckedChange={() => onToggleCompare()} />
        </span>
      )}
      <div className="flex items-center gap-xxs">
        <span className="kq-mono-row text-caption text-text-muted">#{bt.id}</span>
        <Chip
          color={bt.status === 'COMPLETED' ? 'up' : bt.status === 'FAILED' ? 'down' : 'neutral'}
          label={statusLabel(bt.status)}
          size="sm"
        />
      </div>
      <div className="text-body-sm font-semibold text-text-primary">{bt.strategyName ?? '—'}</div>
      <div className="text-caption text-text-muted">
        {bt.symbol} · {bt.intervalValue} · {bt.startTime?.slice(0, 10)} ~ {bt.endTime?.slice(0, 10)}
      </div>
      {bt.status === 'COMPLETED' ? (
        <div className={`kq-mono-row text-body-sm font-semibold ${up ? 'text-up' : 'text-down'}`}>
          {up ? '↑' : '↓'} {fmtPct(bt.totalReturn)}
        </div>
      ) : bt.status === 'RUNNING' ? (
        <div>
          <Progress value={pct} />
          <div className="kq-mono-row text-caption text-text-muted">
            {bt.processedBars ?? 0}/{bt.totalBars ?? 0}
          </div>
        </div>
      ) : bt.status === 'FAILED' ? (
        <div className="text-caption text-down">回测失败 · 查看原因</div>
      ) : (
        <div className="text-caption text-text-muted">排队中</div>
      )}
    </div>
  )
}

/** 列表 rail(卡片横排；COMPLETED 显收益率+↑↓,RUNNING 显进度，PENDING 显排队中)。
 * compareIds/onToggleCompare 提供时 COMPLETED 卡片显对比勾选框(多选后对比)。 */
export function BacktestRail({
  tasks,
  selectedTaskId,
  onSelect,
  compareIds,
  onToggleCompare,
}: {
  tasks: BacktestTaskDto[]
  selectedTaskId: number | null
  onSelect: (taskId: number) => void
  compareIds?: number[]
  onToggleCompare?: (reportId: number) => void
}) {
  return (
    <div className="flex gap-xxs overflow-x-auto pb-xxs">
      {tasks.map((bt) => {
        const comparable = bt.status === 'COMPLETED' && bt.reportId != null && onToggleCompare != null
        return (
          <BacktestCard
            key={bt.id}
            bt={bt}
            selected={bt.id === selectedTaskId}
            onClick={() => onSelect(bt.id)}
            compareChecked={comparable && bt.reportId != null && (compareIds ?? []).includes(bt.reportId)}
            onToggleCompare={comparable && bt.reportId != null ? () => onToggleCompare(bt.reportId!) : undefined}
          />
        )
      })}
    </div>
  )
}
