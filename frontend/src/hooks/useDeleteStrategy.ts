import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'

/**
 * useDeleteStrategy — 删除策略 mutation(spec §5 step 12)。
 * 成功:invalidate ['strategies'] + toast + 跳 / 。
 */
export function useDeleteStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) =>
      apiFetch<null>(`/api/v1/strategies/${id}`, {
        method: 'DELETE',
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['strategies'] })
      toast.success('策略已删除')
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '删除失败,请重试'
      toast.error(msg)
    },
  })
}
