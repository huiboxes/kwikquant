/**
 * MSW envelope helper — 对齐后端 ApiResponse<T> 结构 {code, message, data, traceId}。
 * code === 0 成功;非 0 由 apiFetch(http.ts parseBody)抛 ApiError。
 * traceId 用固定前缀(测试环境稳定,不引入 Math.random)。
 */
export function envelope<T>(data: T, code = 0, message = 'ok'): { code: number; message: string; data: T; traceId: string } {
  return { code, message, data, traceId: 'test-trace' }
}
