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
  codes: (strategyId: number) => ['strategy', 'codes', strategyId] as const,
  codeDetail: (strategyId: number, codeId: number) =>
    ['strategy', 'code', strategyId, codeId] as const,
  lastEdited: () => ['strategy', 'lastEdited'] as const,
}

export const activityKeys = {
  all: ['activity'] as const,
  feed: (limit?: number) => ['activity', 'feed', limit] as const,
}

export const accountKeys = {
  all: ['account'] as const,
  list: () => ['account', 'list'] as const,
  balance: (id: number) => ['account', 'balance', id] as const,
}

export const portfolioKeys = {
  all: ['portfolio'] as const,
  summary: (mode?: string) => ['portfolio', 'summary', mode ?? 'all'] as const,
  pnl: (mode?: string) => ['portfolio', 'pnl', mode ?? 'all'] as const,
  equityCurve: (mode?: string) => ['portfolio', 'equity-curve', mode ?? 'all'] as const,
}

export const marketKeys = {
  all: ['market'] as const,
  ticker: (exchange: string, marketType: string, symbol: string) =>
    ['market', 'ticker', exchange, marketType, symbol] as const,
  pairs: (exchange: string, marketType: string) =>
    ['market', 'pairs', exchange, marketType] as const,
  klines: (q: {
    exchange: string
    marketType: string
    symbol: string
    interval: string
    limit?: number
  }) =>
    [
      'market',
      'klines',
      q.exchange,
      q.marketType,
      q.symbol,
      q.interval,
      q.limit ?? '',
    ] as const,
}

export const backtestKeys = {
  all: ['backtest'] as const,
  reports: (params: { page?: number; pageSize?: number } = {}) =>
    ['backtest', 'reports', params] as const,
  reportDetail: (id: number) => ['backtest', 'report', id] as const,
  compare: () => ['backtest', 'compare'] as const,
  task: (id: number) => ['backtest', 'task', id] as const,
  tasks: (strategyId: number) => ['backtest', 'tasks', strategyId] as const,
}

export const aiKeys = {
  all: ['ai'] as const,
  keys: () => ['ai', 'keys'] as const,
}

export const mcpKeys = {
  all: ['mcp'] as const,
  tokens: () => ['mcp', 'tokens'] as const,
}

export const notifKeys = {
  all: ['notification'] as const,
  preferences: () => ['notification', 'preferences'] as const,
}

export const authKeys = {
  all: ['auth'] as const,
  // change-password 是 mutation,无 cache key;此处占位供未来 auth query 扩展。
}

export const orderKeys = {
  all: ['order'] as const,
  list: (params: { accountId: number; status?: string; page?: number; pageSize?: number }) =>
    ['order', 'list', params.accountId, params.status ?? '', params.page ?? 1, params.pageSize ?? 50] as const,
  fills: (orderId: number) => ['order', 'fills', orderId] as const,
}

export const positionKeys = {
  all: ['position'] as const,
  list: (accountId: number, symbol?: string) =>
    ['position', 'list', accountId, symbol ?? ''] as const,
}
