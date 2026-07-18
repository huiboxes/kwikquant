import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchAccounts,
  fetchAccountBalance,
  createAccount,
  deleteAccount,
  resetPaperAccount,
} from '@/api/account'
import { accountKeys, positionKeys, orderKeys, portfolioKeys } from '@/api/_queryKeys'

/** useAccounts — 当前用户交易所账户列表(apiKey 脱敏)。 */
export function useAccounts() {
  return useQuery({
    queryKey: accountKeys.list(),
    queryFn: fetchAccounts,
  })
}

/** useAccountBalance — 单账户余额快照(per-card,ExchangeAccountView 无余额字段)。 */
export function useAccountBalance(id: number | undefined) {
  return useQuery({
    queryKey: accountKeys.balance(id ?? 0),
    queryFn: () => fetchAccountBalance(id as number),
    enabled: id != null,
  })
}

/** useCreateAccount — 创建账户(POST),成功后 invalidate list。 */
export function useCreateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createAccount,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: accountKeys.list() })
    },
  })
}

/** useDeleteAccount — 删除账户(DELETE 204),成功后 invalidate account 全域。 */
export function useDeleteAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteAccount,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: accountKeys.all })
    },
  })
}

/**
 * useResetPaperAccount — 重置 PAPER 模拟盘(POST /accounts/{id}/paper/reset,TD-045)。
 * 取消活跃订单+清持仓+余额回 10 万。成功后 invalidate 余额/账户列表/持仓/订单/portfolio。
 * 非 PAPER 账户 400(7001)→ apiFetch 抛 ApiError,组件 onError toast。
 */
export function useResetPaperAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (vars: { accountId: number }) => resetPaperAccount(vars.accountId),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: accountKeys.balance(vars.accountId) })
      queryClient.invalidateQueries({ queryKey: accountKeys.list() })
      queryClient.invalidateQueries({ queryKey: positionKeys.all })
      queryClient.invalidateQueries({ queryKey: orderKeys.all })
      queryClient.invalidateQueries({ queryKey: portfolioKeys.all })
    },
  })
}
