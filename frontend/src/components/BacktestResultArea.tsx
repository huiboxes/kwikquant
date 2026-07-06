import { useBacktestTask } from '@/hooks/useBacktestTask'
import { useBacktestReport } from '@/hooks/useBacktestReport'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { Button } from '@/components/ui/button'
import { EquityChart } from './EquityChart'
import { BacktestMetricsPanel } from './BacktestMetricsPanel'
import { TradesTable } from './TradesTable'

/**
 * BacktestResultArea — 回测结果区(spec §5 step 20/23,组装轮询+结果+FAILED UX)。
 *
 * 流程:
 *  - taskId=null:占位"提交后查看结果"
 *  - PENDING/RUNNING:indeterminate progress + "回测进行中…"(不超时,契约 C)
 *  - COMPLETED:拿 reportId → useBacktestReport → EquityChart + MetricsPanel + TradesTable
 *  - FAILED:errorMessage + 重试按钮(onRetry 重新 POST /backtests 新 taskId,spec §4 line 146)
 *
 * 轮询由 useBacktestTask 内部 refetchInterval 驱动(2s/2s/4s/8s/10s 上限)。
 */
export interface BacktestResultAreaProps {
  taskId: number | null
  onRetry?: () => void
  /** 重试 POST 进行中(按钮 disabled + 文案"提交中…",防快速多点击触发多次 POST) */
  isRetrying?: boolean
}

export function BacktestResultArea({ taskId, onRetry, isRetrying }: BacktestResultAreaProps) {
  const { data: task, isLoading, error } = useBacktestTask(taskId)

  if (taskId === null) {
    return (
      <p className="font-body text-body-sm text-text-muted">
        提交回测后在此查看结果(轮询自动刷新,持续到完成或失败)。
      </p>
    )
  }

  if (isLoading) {
    return <LoadingState label="加载任务…" />
  }
  if (error) {
    return (
      <ErrorState
        title="任务加载失败"
        message={error instanceof Error ? error.message : undefined}
      />
    )
  }
  if (!task) return null

  if (task.status === 'FAILED') {
    return (
      <div className="rounded-xl bg-surface-card p-lg shadow-card">
        <p className="text-label-caps uppercase tracking-[0.35em] text-down">Failed</p>
        <h3 className="mt-sm font-display text-h3 text-down">回测失败</h3>
        <p className="mt-md font-body text-body-sm text-text-secondary">
          {task.errorMessage || '未知错误,请重试'}
        </p>
        {onRetry && (
          <Button
            className="mt-lg"
            variant="outline"
            onClick={onRetry}
            disabled={isRetrying}
          >
            {isRetrying ? '提交中…' : '重新提交'}
          </Button>
        )}
      </div>
    )
  }

  if (task.status === 'PENDING' || task.status === 'RUNNING') {
    return (
      <div className="rounded-xl bg-surface-card p-lg shadow-card">
        <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Running</p>
        <h3 className="mt-sm font-display text-h3">回测进行中…</h3>
        <p className="mt-md font-body text-body-sm text-text-secondary">
          回测正在运行,完成后自动展示结果。请勿关闭页面。
        </p>
        <div
          className="mt-lg h-1 w-full overflow-hidden rounded-full bg-surface-card-2"
          role="progressbar"
          aria-label="回测进度"
        >
          <div className="h-full w-1/3 animate-pulse rounded-full bg-accent" />
        </div>
      </div>
    )
  }

  // COMPLETED
  return <BacktestReportView reportId={task.reportId} />
}

/**
 * BacktestReportView — COMPLETED 后渲染报告(spec §5 step 20-22)。
 * export 供测试直接渲染 reportId<=0 fallback 场景。
 */
export function BacktestReportView({ reportId }: { reportId: number | null }) {
  const { data: report, isLoading, error } = useBacktestReport(reportId)
  // reportId 缺失/0(后端契约违规 COMPLETED 但无 reportId)→ 显式错误,不留空白
  if (!reportId || reportId <= 0) {
    return (
      <ErrorState
        title="报告缺失"
        message="任务已完成但未关联报告,请联系管理员"
      />
    )
  }
  if (isLoading) return <LoadingState label="加载报告…" />
  if (error) {
    return (
      <ErrorState
        title="报告加载失败"
        message={error instanceof Error ? error.message : undefined}
      />
    )
  }
  if (!report) return null

  return (
    <div className="space-y-lg">
      <div className="rounded-xl bg-surface-card p-lg shadow-card">
        <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">
          Report #{report.id}
        </p>
        <h3 className="mt-sm font-display text-h3">{report.name}</h3>
        <div className="mt-lg">
          <EquityChart equityCurve={report.equityCurve} />
        </div>
      </div>
      <BacktestMetricsPanel metrics={report.metrics} />
      <TradesTable trades={report.trades} />
    </div>
  )
}
