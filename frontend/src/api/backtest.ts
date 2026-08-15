import { apiFetch, authFetch } from '@/lib/http'
import { parseContentDispositionFilename } from '@/pages/history/parseContentDisposition'
import type { components } from '@/types/api-gen'

/**
 * backtest typed client(reports + tasks;BacktestPage 用)。
 *
 * 端点(均 JWT):
 *  - GET  /api/v1/reports?page=&pageSize= → PageDtoBacktestReportDto(报告列表,COMPLETED,含 metrics 摘要)
 *  - GET  /api/v1/reports/{id}            → BacktestReportDetailDto(metrics + trades + equityCurve)
 *  - GET  /api/v1/reports/{id}/export     → JSON 文件流(导出格式 = import 消费格式,迁移闭环)
 *  - POST /api/v1/reports/compare         → ComparisonResultDto(reports + ranking;reportIds 2-20)
 *  - POST /api/v1/reports/import          → BacktestReportDto(导入外部 JSON 报告)
 *  - POST /api/v1/backtests               → BacktestTaskDto(PENDING,异步;SubmitBacktestRequest)
 *  - GET  /api/v1/backtests/{id}          → BacktestTaskDto(轮询 PENDING→RUNNING→COMPLETED/FAILED)
 *  - GET  /api/v1/backtests?strategyId=   → BacktestTaskDto[](按策略查任务历史)
 *
 * 注:list rail 只展 reports(COMPLETED),不混 RUNNING(跨策略 RUNNING 任务列表端点缺,
 * GET /backtests 需 strategyId required)。提交回测后轮询 COMPLETED → invalidate reports
 * refetch 自然出现新卡。
 */
type BacktestReportDto = components['schemas']['BacktestReportDto']
type BacktestReportDetailDto = components['schemas']['BacktestReportDetailDto']
type ComparisonResultDto = components['schemas']['ComparisonResultDto']
type BacktestTaskDto = components['schemas']['BacktestTaskDto']
type SubmitBacktestRequest = components['schemas']['SubmitBacktestRequest']
type BacktestSubmitRequest = components['schemas']['BacktestSubmitRequest']
type PageDtoBacktestReportDto = components['schemas']['PageDtoBacktestReportDto']

export type {
  BacktestReportDto,
  BacktestReportDetailDto,
  ComparisonResultDto,
  BacktestTaskDto,
  SubmitBacktestRequest,
  BacktestSubmitRequest,
  PageDtoBacktestReportDto,
}

/** 回测任务状态枚举(PENDING→RUNNING→COMPLETED|FAILED;见 docs/behavior-contract.md 回测轮询协议)。 */
export type BacktestTaskStatus = BacktestTaskDto['status']

/**
 * 查询回测报告列表(分页)。COMPLETED 报告含 metrics 摘要(totalReturn/sharpeRatio/...)。
 * list rail 数据源。
 */
export function fetchReports(
  params: { page?: number; pageSize?: number } = {},
): Promise<PageDtoBacktestReportDto> {
  const qs = new URLSearchParams()
  if (params.page != null) qs.set('page', String(params.page))
  if (params.pageSize != null) qs.set('pageSize', String(params.pageSize))
  const q = qs.toString()
  return apiFetch<PageDtoBacktestReportDto>(`/api/v1/reports${q ? `?${q}` : ''}`)
}

/** 查回测报告详情(完整 metrics + trades + equityCurve)。单报告模式 EquityCurve/MetricGrid/TradeList 用。 */
export function fetchReportDetail(id: number): Promise<BacktestReportDetailDto> {
  return apiFetch<BacktestReportDetailDto>(`/api/v1/reports/${id}`)
}

/** 对比 N 个报告(POST /reports/compare;reportIds 2-20,BacktestPage 限 2 照原型)。返 reports + ranking。 */
export function compareReports(reportIds: number[]): Promise<ComparisonResultDto> {
  return apiFetch<ComparisonResultDto>('/api/v1/reports/compare', {
    method: 'POST',
    body: { reportIds },
  })
}

/** 导入外部 JSON 回测报告(POST /reports/import)。BacktestPage "导入"按钮接此(parseImportReport 校验后 mutate)。 */
export function importReport(req: BacktestSubmitRequest): Promise<BacktestReportDto> {
  return apiFetch<BacktestReportDto>('/api/v1/reports/import', { method: 'POST', body: req })
}

/**
 * 导出回测报告 JSON(GET /reports/{id}/export,文件流)。
 *
 * 导出格式 = import 消费格式(BacktestSubmitRequest),下载物可直接再导入(迁移闭环)。
 * 用 authFetch(带 Bearer + 401 refresh 重试;返文件流不 parseBody)。返 { blob, filename };
 * filename 从 Content-Disposition 解析,失败 null 由调用方兜默认名。
 */
export async function exportReport(id: number): Promise<{ blob: Blob; filename: string | null }> {
  const res = await authFetch(`/api/v1/reports/${id}/export`)
  if (!res.ok) throw new Error(`export failed: ${res.status}`)
  const filename = parseContentDispositionFilename(res.headers.get('content-disposition'))
  const blob = await res.blob()
  return { blob, filename }
}

/** 提交回测任务(POST /backtests,异步返 PENDING task;前端用 taskId 轮询 GET /backtests/{id})。 */
export function submitBacktest(req: SubmitBacktestRequest): Promise<BacktestTaskDto> {
  return apiFetch<BacktestTaskDto>('/api/v1/backtests', { method: 'POST', body: req })
}

/** 查回测任务(轮询用;PENDING→RUNNING→COMPLETED/FAILED,COMPLETED 时 reportId 回填)。 */
export function fetchBacktestTask(id: number): Promise<BacktestTaskDto> {
  return apiFetch<BacktestTaskDto>(`/api/v1/backtests/${id}`)
}

/** 按策略查任务历史(GET /backtests?strategyId=)。 */
export function listBacktestTasks(strategyId: number): Promise<BacktestTaskDto[]> {
  return apiFetch<BacktestTaskDto[]>(`/api/v1/backtests?strategyId=${strategyId}`)
}

/** 查当前用户全部回测任务(GET /backtests,不带 strategyId;带 totalReturn+strategyName,供回测 tab rail)。 */
export function fetchBacktestList(): Promise<BacktestTaskDto[]> {
  return apiFetch<BacktestTaskDto[]>('/api/v1/backtests')
}
