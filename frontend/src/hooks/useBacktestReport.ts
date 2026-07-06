import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

export type BacktestReportDetailDto = components['schemas']['BacktestReportDetailDto']

/**
 * useBacktestReport — 回测报告详情 query(spec §5 step 20)。
 *
 * GET /api/v1/reports/:id → BacktestReportDetailDto(metrics/trades/equityCurve)。
 * COMPLETED 时由 BacktestTaskDto.reportId(契约 B)触发。
 *
 * cache key: ['reports', reportId]。
 */
export function useBacktestReport(reportId: number | null) {
  return useQuery({
    queryKey: ['reports', reportId],
    queryFn: () => apiFetch<BacktestReportDetailDto>(`/api/v1/reports/${reportId}`),
    enabled: reportId !== null && reportId > 0,
  })
}
