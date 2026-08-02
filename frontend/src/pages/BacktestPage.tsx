import { useMemo } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/EmptyState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { useBacktestList } from '@/hooks/useBacktest'
import { BacktestRail } from './backtest/BacktestRail'
import { BacktestDetail } from './backtest/BacktestDetail'

/**
 * BacktestPage — 回测 tab 独立页(照原型 BacktestPage.jsx port)。
 * 列表 rail(全策略回测,带 totalReturn+strategyName)+ 选中详情(指标/曲线/明细/导出)。
 * URL query reportId 双向同步(rail 点击/带 query 打开)。无 query 默认选第一张 COMPLETED。
 */
export function BacktestPage() {
  const navigate = useNavigate()
  const { data: tasks, isLoading, error } = useBacktestList()
  const [params, setParams] = useSearchParams()
  const reportIdParam = params.get('reportId')
  const reportId = reportIdParam ? parseInt(reportIdParam, 10) : null

  const firstCompleted = useMemo(
    () => (tasks ?? []).find((t) => t.status === 'COMPLETED' && t.reportId != null),
    [tasks],
  )
  const effectiveReportId = reportId ?? firstCompleted?.reportId ?? null

  const onSelect = (id: number) => setParams({ reportId: String(id) })

  return (
    <div className="flex flex-col gap-md p-md">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-h1 font-semibold text-text-primary">回测</h1>
          <p className="text-caption text-text-muted">用历史数据验证策略表现</p>
        </div>
        <Button onClick={() => navigate('/strategy')}>新建回测</Button>
      </div>

      {/* Rail */}
      {isLoading ? (
        <LoadingState rows={3} />
      ) : error ? (
        <ErrorState title="加载失败" message={error.message} />
      ) : !tasks || tasks.length === 0 ? (
        <EmptyState
          title="暂无回测"
          description="运行回测后结果将显示在这里"
          action={<Button onClick={() => navigate('/strategy')}>去策略页发起新回测</Button>}
        />
      ) : (
        <BacktestRail tasks={tasks} selectedReportId={effectiveReportId} onSelect={onSelect} />
      )}

      {/* Detail(内部自己调 useReportDetail,reportId 指向 RUNNING 显进度态) */}
      <BacktestDetail reportId={effectiveReportId} tasks={tasks ?? []} />
    </div>
  )
}
