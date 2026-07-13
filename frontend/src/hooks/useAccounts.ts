import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchAccounts,
  fetchAccountBalance,
  createAccount,
  deleteAccount,
} from '@/api/account'
import { accountKeys } from '@/api/_queryKeys'

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
