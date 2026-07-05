import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

export type StrategyDetailDto = components['schemas']['StrategyDetailDto']

/**
 * useStrategies — 策略列表 query(spec §5 step 11)。
 * cache key: ['strategies']。staleTime 5s(queryClient 默认)。
 */
export function useStrategies() {
  return useQuery({
    queryKey: ['strategies'],
    queryFn: () => apiFetch<StrategyDetailDto[]>('/api/v1/strategies'),
  })
}
