import { useState } from 'react'
import { useBacktestTask } from '@/hooks/useBacktestTask'
import { useBacktestReport } from '@/hooks/useBacktestReport'
import { EquityChart } from '@/components/EquityChart'
import { BacktestResultArea } from '@/components/BacktestResultArea'
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { LoadingState } from '@/components/feedback/LoadingState'
import { formatPercent, formatRatio, formatDrawdown } from '@/lib/backtestFormat'
import { cn } from '@/lib/utils'

/**
 * BacktestResultPanel — 右栏 340px 紧凑版回测结果(spec §2.4)。
 *
 * 五态:
 *  - taskId null:空态(引导点 Run Live)
 *  - loading:LoadingState
 *  - PENDING/RUNNING:进度提示
 *  - FAILED:errorMessage + 重试按钮
 *  - COMPLETED:头部+3 指标卡(收益/夏普/回撤)+ 面积图 + 查看详情 Dialog(显完整 BacktestResultArea)
 *
 * 紧凑版直接显 3 指标卡(不用 BacktestMetricsPanel,它是 7 指标完整面板,详情 Dialog 内复用)。
 */
export function BacktestResultPanel({
  taskId,
  onRetry,
  isRetrying,
}: {
  taskId: number | null
  onRetry?: () => void
  isRetrying?: boolean
}) {
  const { data: task, isLoading } = useBacktestTask(taskId)
  const reportId = task?.reportId ?? null
  const { data: report } = useBacktestReport(reportId)
  const [detailOpen, setDetailOpen] = useState(false)

  if (taskId === null) {
    return (
      <div className="rounded-xl bg-surface-card p-lg text-center">
        <p className="font-body text-body-sm text-text-secondary">还没有回测结果</p>
        <p className="mt-sm font-body text-body-sm text-text-muted">点 Run Live 跑回测</p>
      </div>
    )
  }

  if (isLoading && !task) return <LoadingState label="加载回测状态" rows={2} />

  if (task?.status === 'FAILED') {
    return (
      <div className="rounded-xl bg-surface-card p-lg">
        <div className="flex items-center gap-sm">
          <span className="h-[6px] w-[6px] rounded-full bg-down" />
          <span className="font-body text-body-sm">回测失败</span>
        </div>
        <p className="mt-md font-mono text-body-sm text-text-secondary">
          {task.errorMessage || '未知错误'}
        </p>
        {onRetry && (
          <Button
            className="mt-md"
            variant="outline"
            disabled={isRetrying}
            onClick={onRetry}
          >
            {isRetrying ? '重试中…' : '重试'}
          </Button>
        )}
      </div>
    )
  }

  if (task?.status === 'PENDING' || task?.status === 'RUNNING') {
    return (
      <div className="rounded-xl bg-surface-card p-lg">
        <div className="flex items-center gap-sm">
          <span className="h-[6px] w-[6px] animate-pulse rounded-full bg-warning" />
          <span className="font-body text-body-sm">回测进行中…</span>
        </div>
      </div>
    )
  }

  if (task?.status === 'COMPLETED' && reportId !== null) {
    const metrics = report?.metrics
    return (
      <div className="rounded-xl bg-surface-card p-lg">
        <div className="flex items-center justify-between">
          <span className="font-display text-h3">回测结果</span>
          <Badge variant="secondary" className="gap-xs">
            <span className="h-[6px] w-[6px] rounded-full bg-up" />
            Complete
          </Badge>
        </div>
        {metrics && (
          <div className="mt-md grid grid-cols-3 gap-sm">
            <MetricCard
              label="收益"
              value={formatPercent(metrics.totalReturn)}
              tone={metrics.totalReturn >= 0 ? 'up' : 'down'}
            />
            <MetricCard label="夏普" value={formatRatio(metrics.sharpeRatio)} />
            <MetricCard
              label="回撤"
              value={formatDrawdown(metrics.maxDrawdown)}
              tone="down"
            />
          </div>
        )}
        {report && (
          <div className="mt-md h-[180px]">
            <EquityChart equityCurve={report.equityCurve} />
          </div>
        )}
        <Dialog open={detailOpen} onOpenChange={setDetailOpen}>
          <DialogTrigger asChild>
            <Button variant="outline" className="mt-md w-full">
              查看详情
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-5xl">
            <BacktestResultArea
              taskId={taskId}
              onRetry={onRetry}
              isRetrying={isRetrying}
            />
          </DialogContent>
        </Dialog>
      </div>
    )
  }

  return null
}

function MetricCard({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone?: 'up' | 'down'
}) {
  return (
    <div className="rounded-md bg-surface-card-2 p-sm text-center">
      <p className="text-caption text-text-muted">{label}</p>
      <p
        className={cn(
          'mt-xs font-mono text-body-sm',
          tone === 'up' ? 'text-up' : tone === 'down' ? 'text-down' : 'text-text-primary',
        )}
      >
        {value}
      </p>
    </div>
  )
}
