import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchReports,
  fetchReportDetail,
  compareReports,
  importReport,
  submitBacktest,
  fetchBacktestTask,
} from '@/api/backtest'
import { backtestKeys } from '@/api/_queryKeys'
import type { BacktestTaskDto, SubmitBacktestRequest } from '@/api/backtest'

/**
 * useBacktest — reports 列表 + 报告详情 + 对比 + 提交 + 任务轮询 + 导入(BacktestPage 用)。
 *
 * 轮询协议(behavior-contract §3):POST /backtests → taskId → 轮询 GET /backtests/{id}
 * 指数退避 2s/2s/4s/8s(上限 10s),持续到 COMPLETED/FAILED,不超时(回测可能跑几分钟,
 * 60s 兜底致用户误以为失败重复提交压死 Worker;仅对"5 分钟 status 无变化"提示异常——
 * 该异常提示在 page 层 useEffect 实现,hook 只负责轮询)。
 */

/** 报告列表(COMPLETED,分页)。list rail 数据源。 */
export function useReports(params: { page?: number; pageSize?: number } = {}) {
  return useQuery({
    queryKey: backtestKeys.reports(params),
    queryFn: () => fetchReports(params),
  })
}

/** 报告详情(metrics + trades + equityCurve)。单报告模式 EquityCurve/MetricGrid/TradeList 用。 */
export function useReportDetail(id: number | null) {
  return useQuery({
    queryKey: backtestKeys.reportDetail(id ?? -1),
    queryFn: () => fetchReportDetail(id!),
    enabled: id != null,
  })
}

/** 对比 N 个报告(mutation)。compareMode 用。reportIds 2-20,page 层限 2 照原型。 */
export function useCompareReports() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (reportIds: number[]) => compareReports(reportIds),
    onSuccess: () => qc.invalidateQueries({ queryKey: backtestKeys.compare() }),
  })
}

/** 提交回测任务(mutation;返 PENDING task,page 层用 taskId 启 useBacktestTask 轮询)。 */
export function useSubmitBacktest() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: SubmitBacktestRequest) => submitBacktest(req),
    onSuccess: () => qc.invalidateQueries({ queryKey: backtestKeys.all }),
  })
}

/** 指数退避间隔(behavior-contract §3:2s/2s/4s/8s,上限 10s)。 */
const POLL_INTERVALS = [2000, 2000, 4000, 8000]

/**
 * 轮询回测任务(指数退避 2s/2s/4s/8s 上限 10s;COMPLETED/FAILED 停)。
 * 仅 taskId 存在时启用。终态副作用(invalidate reports + setSelected reportId)在 page 层
 * useEffect[task?.status] 实现,hook 只负责轮询 + 停。
 */
export function useBacktestTask(taskId: number | null) {
  return useQuery({
    queryKey: backtestKeys.task(taskId ?? -1),
    queryFn: () => fetchBacktestTask(taskId!),
    enabled: taskId != null,
    refetchInterval: (query) => {
      const task = query.state.data as BacktestTaskDto | undefined
      if (task && (task.status === 'COMPLETED' || task.status === 'FAILED')) return false
      const idx = Math.min(query.state.dataUpdateCount, POLL_INTERVALS.length - 1)
      return Math.min(POLL_INTERVALS[idx] ?? 10000, 10000)
    },
  })
}

/** 导入外部报告(mutation;BacktestPage "导入"按钮接此,onSuccess 自动 invalidate reports)。 */
export function useImportReport() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: importReport,
    onSuccess: () => qc.invalidateQueries({ queryKey: backtestKeys.reports() }),
  })
}
