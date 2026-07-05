import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './http'

/**
 * QueryClient 默认配置(spec §5 step 6)。
 *
 * cache key 规约:[entity, id?, params?]
 *   ['strategies']                - 策略列表
 *   ['strategies', id]            - 策略详情
 *   ['strategies', id, 'codes']   - 策略代码列表
 *   ['strategies', id, 'codes', codeId] - 代码详情
 *   ['ai-keys']                   - AI key 列表
 *   ['backtests', id]             - 回测任务
 *
 * mutation invalidate 规约:
 *   POST   /strategies             → invalidate ['strategies']
 *   PUT    /strategies/:id         → invalidate ['strategies', id]
 *   DELETE /strategies/:id         → invalidate ['strategies']
 *   POST   /:codeId/publish        → invalidate ['strategies', id, 'codes']
 *   POST   /backtests              → invalidate ['backtests']
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      refetchOnWindowFocus: false,
      // 401/403 不重试(认证错误重试无意义);其余错误重试 1 次
      retry: (failureCount, error) => {
        if (error instanceof ApiError && (error.isUnauthorized || error.isForbidden)) {
          return false
        }
        return failureCount < 1
      },
    },
    mutations: {
      retry: 0,
    },
  },
})
