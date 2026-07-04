/**
 * TanStack Query 共享重试策略：401/403 不重试（auth 错误靠 http 层处理），
 * 其他错误最多重试 2 次。各 hooks 统一引用，改一处生效全局。
 *
 * 脚手架阶段用 duck-typing 判断 error.status（未依赖 ApiError 类）；
 * 业务阶段引入 @/api/errors.ApiError 后，可改为 error instanceof ApiError && error.isUnauthorized。
 */
export function defaultRetry(failureCount: number, error: unknown): boolean {
  const status = (error as { status?: unknown } | null | undefined)?.status
  if (status === 401 || status === 403) return false
  return failureCount < 2
}
