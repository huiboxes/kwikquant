/**
 * 集中 react-query cache key 工厂(规约见 src/lib/queryClient.ts 注释)。
 *
 * 按域组织,避免 key 漂移导致缓存失效/重复请求。list/detail/stats 用层级 key
 * (['domain'] / ['domain','list',params] / ['domain','detail',id]),react-query
 * 默认 staleTime 5s(见 queryClient.ts)。
 *
 * 按页驱动:每页 port 时追加对应域的 keys。
 */

export const tradeHistoryKeys = {
  all: ['trade-history'] as const,
  list: (params: object = {}) => ['trade-history', 'list', params] as const,
  stats: (params: object = {}) => ['trade-history', 'stats', params] as const,
}

export const riskKeys = {
  all: ['risk'] as const,
  list: () => ['risk', 'policies'] as const,
  decisions: (params: object = {}) => ['risk', 'decisions', params] as const,
}

export const strategyKeys = {
  all: ['strategy'] as const,
  list: () => ['strategy', 'list'] as const,
  detail: (id: number) => ['strategy', 'detail', id] as const,
}
