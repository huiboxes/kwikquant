import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

export type StrategyCodeDto = components['schemas']['StrategyCodeDto']
export type StrategyCodeDetailDto = components['schemas']['StrategyCodeDetailDto']

/**
 * useStrategyCodes — 策略代码列表 query(spec §5 step 15)。
 * cache key: ['strategies', strategyId, 'codes']。
 * 工作区 mount 用此找 DRAFT codeId,再 GET detail 拉源码。
 */
export function useStrategyCodes(strategyId: number | null) {
  return useQuery({
    queryKey: ['strategies', strategyId, 'codes'],
    queryFn: () => apiFetch<StrategyCodeDto[]>(`/api/v1/strategies/${strategyId}/codes`),
    enabled: strategyId !== null,
  })
}

/**
 * useStrategyCode — 代码详情 query(契约 A,含 sourceCode)。
 * cache key: ['strategies', strategyId, 'codes', codeId]。
 */
export function useStrategyCode(strategyId: number | null, codeId: number | null) {
  return useQuery({
    queryKey: ['strategies', strategyId, 'codes', codeId],
    queryFn: () =>
      apiFetch<StrategyCodeDetailDto>(
        `/api/v1/strategies/${strategyId}/codes/${codeId}`,
      ),
    enabled: strategyId !== null && codeId !== null,
  })
}
