import { useStrategies } from './useStrategies'
import type { StrategyDetailDto } from './useStrategies'

/**
 * useStrategy — 从策略列表缓存取单个 strategy(by id)。
 * 复用 useStrategies cache(key ['strategies']),不额外发请求。
 * 列表未加载或无匹配 → data undefined。
 */
export function useStrategy(id: number | null) {
  const { data, ...rest } = useStrategies()
  return {
    data: id === null ? undefined : data?.find((s) => s.id === id),
    ...rest,
  }
}

export type { StrategyDetailDto }
