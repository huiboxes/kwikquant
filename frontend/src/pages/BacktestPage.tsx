import { useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/EmptyState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { useBacktestList, useCompareReports, useImportReport } from '@/hooks/useBacktest'
import { BacktestRail } from './backtest/BacktestRail'
import { BacktestDetail } from './backtest/BacktestDetail'
import { CompareDialog } from './backtest/CompareDialog'
import { parseImportReport } from './backtest/parseImportReport'
import { ApiError } from '@/lib/http'

/** 对比上限(后端 ReportComparisonService.MAX_REPORTS)。 */
const MAX_COMPARE = 20

/**
 * BacktestPage — 回测 tab 独立页(照原型 BacktestPage.jsx port)。
 * 列表 rail(全策略回测，带 totalReturn+strategyName)+ 选中详情(指标/曲线/明细/导出)。
 * URL query reportId 双向同步(rail 点击/带 query 打开)。无 query 默认选第一张 COMPLETED。
 *
 * 报告 trio(Wave 3.1):卡片勾选多选 → 对比(useCompareReports)；头部"导入"按钮 →
 * parseImportReport 前端校验 → useImportReport；详情卡"导出 JSON"(导出格式=导入格式闭环)。
 */
export function BacktestPage() {
  const navigate = useNavigate()
  const { data: tasks, isLoading, error, refetch } = useBacktestList()
  const [params, setParams] = useSearchParams()
  const reportIdParam = params.get('reportId')
  const reportId = reportIdParam ? parseInt(reportIdParam, 10) : null
  const taskIdParam = params.get('taskId')
  const taskId = taskIdParam ? parseInt(taskIdParam, 10) : null

  const firstCompleted = useMemo(
    () => (tasks ?? []).find((t) => t.status === 'COMPLETED' && t.reportId != null),
    [tasks],
  )
  const selectedTask = useMemo(
    () =>
      (taskId != null ? (tasks ?? []).find((task) => task.id === taskId) : undefined) ??
      (reportId != null ? (tasks ?? []).find((task) => task.reportId === reportId) : undefined) ??
      firstCompleted,
    [firstCompleted, reportId, taskId, tasks],
  )
  const effectiveTaskId = selectedTask?.id ?? null
  const effectiveReportId = selectedTask?.reportId ?? reportId ?? null

  const onSelect = (id: number) => setParams({ taskId: String(id) })

  // ─── 对比(多选 reportIds → useCompareReports → CompareDialog)───
  const [compareIds, setCompareIds] = useState<number[]>([])
  const [showCompare, setShowCompare] = useState(false)
  const compareMut = useCompareReports()

  const toggleCompare = (id: number) => {
    setCompareIds((prev) => {
      if (prev.includes(id)) return prev.filter((x) => x !== id)
      if (prev.length >= MAX_COMPARE) {
        toast.warning(`最多对比 ${MAX_COMPARE} 个报告`)
        return prev
      }
      return [...prev, id]
    })
  }
  const openCompare = () => {
    if (compareIds.length < 2) {
      toast.warning('请勾选至少 2 个已完成的回测')
      return
    }
    setShowCompare(true)
    compareMut.mutate(compareIds)
  }

  // ─── 导入(文件 → parseImportReport 校验 → useImportReport)───
  const importMut = useImportReport()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const onImportFile = async (file: File) => {
    const text = await file.text()
    const parsed = parseImportReport(text)
    if (!parsed.ok) {
      toast.error('导入失败', { description: parsed.error })
      return
    }
    importMut.mutate(parsed.data, {
      onSuccess: (report) => {
        toast.success('报告已导入', { description: report.name })
        setParams({ reportId: String(report.id) })
      },
      onError: (err) => {
        const msg = err instanceof ApiError ? err.message : '导入失败，请检查文件格式'
        toast.error('导入失败', { description: msg })
      },
    })
  }

  return (
    <div className="flex flex-col gap-md p-md">
      {/* Header — 窄屏按钮组换行到标题下方 */}
      <div className="flex flex-wrap items-center justify-between gap-sm">
        <div>
          <h1 className="text-h1 font-semibold text-text-primary">回测</h1>
          <p className="text-caption text-text-muted">用历史数据验证策略表现</p>
        </div>
        <div className="flex flex-wrap gap-xs">
          {/* 导入：隐藏 file input,accept .json */}
          <input
            ref={fileInputRef}
            type="file"
            accept=".json,application/json"
            className="hidden"
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) void onImportFile(f)
              e.target.value = '' // 允许重复选同一文件
            }}
          />
          <Button
            variant="outline"
            onClick={() => fileInputRef.current?.click()}
            disabled={importMut.isPending}
          >
            {importMut.isPending ? '导入中…' : '导入报告'}
          </Button>
          <Button variant="outline" onClick={openCompare} disabled={compareIds.length < 2}>
            对比{compareIds.length > 0 ? ` (${compareIds.length})` : ''}
          </Button>
          <Button onClick={() => navigate('/strategy')}>新建回测</Button>
        </div>
      </div>

      {/* Rail(COMPLETED 卡片带对比勾选框) */}
      {isLoading ? (
        <LoadingState rows={3} />
      ) : error ? (
        <ErrorState title="加载失败" message="暂时无法加载回测记录，请稍后重试" onRetry={() => refetch()} />
      ) : !tasks || tasks.length === 0 ? (
        <EmptyState
          title="暂无回测"
          description="运行回测后结果将显示在这里"
          action={<Button onClick={() => navigate('/strategy')}>去策略页发起新回测</Button>}
        />
      ) : (
        <BacktestRail
          tasks={tasks}
          selectedTaskId={effectiveTaskId}
          onSelect={onSelect}
          compareIds={compareIds}
          onToggleCompare={toggleCompare}
        />
      )}

      {/* Detail(内部自己调 useReportDetail,reportId 指向 RUNNING 显进度态) */}
      <BacktestDetail reportId={effectiveReportId} selectedTaskId={effectiveTaskId} tasks={tasks ?? []} />

      {/* 对比弹窗 */}
      <CompareDialog
        open={showCompare}
        onOpenChange={setShowCompare}
        result={compareMut.data}
        isLoading={compareMut.isPending}
        error={compareMut.error}
      />
    </div>
  )
}
