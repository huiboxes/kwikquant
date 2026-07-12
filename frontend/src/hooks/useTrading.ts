import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchOrders,
  listFills,
  cancelOrder,
  submitOrder,
} from '@/api/order'
import { fetchPositions } from '@/api/position'
import { orderKeys } from '@/api/_queryKeys'
import { positionKeys, accountKeys } from '@/api/_queryKeys'

/**
 * trading hooks(TradingPage 用)。
 *
 * honest:WS 推送(/topic/orders/{userId} + /topic/fills/{userId})未接(TD-046),
 * 当前用 react-query invalidate 在 mutation 成功后刷新列表。WS 推送归 layout 数据接线阶段补。
 */

/** useOrders — 分页查订单(accountId 必填,可选 status/page/pageSize/symbol)。 */
export function useOrders(
  accountId: number | null,
  opts?: { status?: string; page?: number; pageSize?: number; symbol?: string },
) {
  return useQuery({
    queryKey: orderKeys.list({
      accountId: accountId ?? 0,
      status: opts?.status,
      page: opts?.page,
      pageSize: opts?.pageSize,
    }),
    queryFn: () =>
      fetchOrders({
        accountId: accountId as number,
        symbol: opts?.symbol,
        status: opts?.status,
        page: opts?.page,
        pageSize: opts?.pageSize,
      }),
    enabled: accountId != null,
  })
}

/** usePositions — 单账户持仓(accountId 必填,可选 symbol 过滤)。 */
export function usePositions(accountId: number | null, symbol?: string) {
  return useQuery({
    queryKey: positionKeys.list(accountId ?? 0, symbol),
    queryFn: () => fetchPositions(accountId as number, symbol),
    enabled: accountId != null,
  })
}

/** useOrderFills — 单订单成交明细(orderId 必填)。 */
export function useOrderFills(orderId: number | null) {
  return useQuery({
    queryKey: orderKeys.fills(orderId ?? 0),
    queryFn: () => listFills(orderId as number),
    enabled: orderId != null,
  })
}

/** useCancelOrder — 撤单(DELETE 202)。成功后 invalidate 订单列表。 */
export function useCancelOrder() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: cancelOrder,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: orderKeys.all })
    },
  })
}

/**
 * useSubmitOrder — 提交订单(POST 201/4105)。
 * 成功(201)→ invalidate 订单 + 持仓(余额由 BalanceBar useAccountBalance 各自管理,不在此 invalidate)。
 * 风控拒(200+code=4105)→ apiFetch 抛 ApiError(4105),组件 mut.mutate(req,{onError}) 检查 e.code===4105。
 * hook 不写 onError,留给组件层处理(需 navigate('/risk') + toast,组件有 useNavigate)。
 */
export function useSubmitOrder() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: submitOrder,
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: orderKeys.all })
      queryClient.invalidateQueries({ queryKey: positionKeys.all })
      queryClient.invalidateQueries({ queryKey: accountKeys.balance(variables.accountId) })
    },
  })
}
