import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

export type ExchangeAccountView = components['schemas']['ExchangeAccountView']

/**
 * useExchangeAccounts — 当前用户交易所账户列表(GET /api/v1/accounts)。
 * 用途:StrategyNew 前置校验(至少一个模拟盘账户)+ Accounts 页展示。
 */
export function useExchangeAccounts() {
  return useQuery({
    queryKey: ['accounts'],
    queryFn: () => apiFetch<ExchangeAccountView[]>('/api/v1/accounts'),
  })
}
