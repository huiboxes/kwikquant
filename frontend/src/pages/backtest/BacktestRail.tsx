import { Chip } from '@/components/Chip'
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

/** 单个回测卡片(照原型 BacktestPage.jsx 110-146 port;砍 Sparkline 用真实收益率)。 */
export function BacktestCard({
  bt,
  selected,
  onClick,
}: {
  bt: BacktestTaskDto
  selected: boolean
  onClick: () => void
}) {
  const up = bt.totalReturn != null && toDecimal(bt.totalReturn).gte(0)
  const pct = bt.totalBars ? Math.round(((bt.processedBars ?? 0) / bt.totalBars) * 100) : 0
  return (
    <div
      data-selected={selected}
      onClick={onClick}
      className={`flex flex-[0_0_240px] flex-col gap-xxs rounded-lg border p-sm cursor-pointer ${selected ? 'border-brand bg-brand-soft' : 'border-hair bg-surface-card'}`}
    >
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
      ) : (
        <div className="text-caption text-text-muted">排队中</div>
      )}
    </div>
  )
}

/** 列表 rail(卡片横排;COMPLETED 显收益率+↑↓,RUNNING 显进度,PENDING 显排队中)。 */
export function BacktestRail({
  tasks,
  selectedReportId,
  onSelect,
}: {
  tasks: BacktestTaskDto[]
  selectedReportId: number | null
  onSelect: (reportId: number) => void
}) {
  return (
    <div className="flex gap-xxs overflow-x-auto pb-xxs">
      {tasks.map((bt) => (
        <BacktestCard
          key={bt.id}
          bt={bt}
          selected={bt.reportId === selectedReportId}
          onClick={() => bt.reportId != null && onSelect(bt.reportId)}
        />
      ))}
    </div>
  )
}
