import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'
import type { ExchangeAccountView } from './useExchangeAccounts'

export interface CreateAccountInput {
  exchange: 'PAPER' | 'BINANCE' | 'BITGET' | 'OKX'
  label: string
  apiKey: string
  apiSecret: string
  passphrase?: string
}

/**
 * useCreateExchangeAccount — 创建交易所账户(POST /api/v1/accounts)。
 * 成功 invalidate ['accounts'] + toast;失败 toast 后端 message。
 */
export function useCreateExchangeAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateAccountInput) =>
      apiFetch<ExchangeAccountView>('/api/v1/accounts', {
        method: 'POST',
        body: input,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      toast.success('账户已添加')
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '添加账户失败,请重试'
      toast.error(msg)
    },
  })
}
