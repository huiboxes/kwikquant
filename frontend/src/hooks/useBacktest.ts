import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchReports,
  fetchReportDetail,
  compareReports,
  importReport,
  submitBacktest,
  fetchBacktestTask,
  listBacktestTasks,
  fetchBacktestList,
} from '@/api/backtest'
import { backtestKeys } from '@/api/_queryKeys'
import type { BacktestTaskDto, SubmitBacktestRequest } from '@/api/backtest'

/**
 * useBacktest — reports 列表 + 报告详情 + 对比 + 提交 + 任务轮询 + 导入(BacktestPage 用)。
 *
 * 轮询协议(见 docs/behavior-contract.md 回测轮询协议):POST /backtests → taskId → 轮询 GET /backtests/{id}
 * 指数退避 2s/2s/4s/8s(上限 10s)，持续到 COMPLETED/FAILED，不超时(回测可能跑几分钟，
 * 60s 兜底致用户误以为失败重复提交压死 Worker；仅对"5 分钟 status 无变化"提示异常——
 * 该异常提示在 page 层 useEffect 实现，hook 只负责轮询)。
 */

/** 报告列表(COMPLETED，分页)。list rail 数据源。 */
export function useReports(params: { page?: number; pageSize?: number } = {}) {
  return useQuery({
    queryKey: backtestKeys.reports(params),
    queryFn: () => fetchReports(params),
  })
}

/**
 * 当前用户全部回测任务(GET /backtests 不带 strategyId，带 totalReturn+strategyName,
 * 供回测 tab 列表 rail)。有 RUNNING/PENDING 时 5s 轮询刷新(列表轮询兜底 WS，跟
 * useBacktestTasksByStrategy 一致)；全终态停。
 */
export function useBacktestList() {
  return useQuery({
    queryKey: backtestKeys.tasksAll,
    queryFn: fetchBacktestList,
    refetchInterval: (query) => {
      const tasks = query.state.data
      return tasks?.some((t) => t.status === 'RUNNING' || t.status === 'PENDING') ? 5000 : false
    },
  })
}

/**
 * 按策略查回测任务历史(GET /backtests?strategyId=，最新在前 ORDER BY created_at DESC)。
 * BacktestPanel 按策略过滤报告用：取该策略最新 COMPLETED task.reportId → useReportDetail。
 * 切策略 query key 变自动 refetch，避免全局最新报告残留(reports 表无
 * strategyId，但 backtest_tasks 有 strategy_id + report_id，走 task 间接关联)。
 */
export function useBacktestTasksByStrategy(strategyId: number | null) {
  return useQuery({
    queryKey: backtestKeys.tasks(strategyId ?? -1),
    queryFn: () => listBacktestTasks(strategyId!),
    enabled: strategyId != null,
    // 列表轮询兜底 WS:有 RUNNING/PENDING task 就 5s refetch(刷新后/WS 失联场景，后端任务
    // 仍在跑但前端 backtestTaskId 内存态丢)；全终态停(无活跃 task 不轮询省请求)。
    refetchInterval: (query) => {
      const tasks = query.state.data
      return tasks?.some((t) => t.status === 'RUNNING' || t.status === 'PENDING') ? 5000 : false
    },
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

/** 提交回测任务(mutation；返 PENDING task,page 层用 taskId 启 useBacktestTask 轮询)。 */
export function useSubmitBacktest() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: SubmitBacktestRequest) => submitBacktest(req),
    onSuccess: () => qc.invalidateQueries({ queryKey: backtestKeys.all }),
  })
}

/** 指数退避间隔(2s/2s/4s/8s，上限 10s)。 */
const POLL_INTERVALS = [2000, 2000, 4000, 8000]

/**
 * 轮询回测任务(指数退避 2s/2s/4s/8s 上限 10s;COMPLETED/FAILED 停)。
 * 仅 taskId 存在时启用。终态副作用(invalidate reports + setSelected reportId)在 page 层
 * useEffect[task?.status] 实现，hook 只负责轮询 + 停。
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

/** 导入外部报告(mutation;BacktestPage "导入"按钮接此，onSuccess 自动 invalidate reports)。 */
export function useImportReport() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: importReport,
    onSuccess: () => qc.invalidateQueries({ queryKey: backtestKeys.reports() }),
  })
}
