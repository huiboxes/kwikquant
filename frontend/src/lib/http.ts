import { useAuthStore } from '@/stores/authStore'

/**
 * ApiError — 后端 ApiResponse envelope 错误(code + message)。
 * ErrorCode 常量见 SCAFFOLD-REF 权威枚举段(0=SUCCESS,1xxx=认证,...)。
 */
export class ApiError extends Error {
  readonly code: number
  readonly status?: number
  constructor(code: number, message: string, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }

  get isUnauthorized(): boolean {
    return this.code === 1001 || this.status === 401
  }

  get isForbidden(): boolean {
    return this.code === 1002 || this.status === 403
  }
}

/**
 * 后端 ApiResponse envelope 双解包:
 * - ApiResponse: {code, message, data, traceId} → code !== 0 抛 ApiError,code === 0 返 data
 * - 裸 body(auth 端点特殊):直接返 body
 *
 * 注意:错误响应按真实后端契约**可能不带 data 字段**(如 401 {code:1001, message, traceId}),
 * 所以 envelope 检测只看 code 是否数字,**不能要求 data 字段存在** —— 否则 401 错误响应
 * 会因 'data' in body=false 走裸 body 路径被当成功返回(曾致"随便输入都登录成功但不跳转"bug)。
 * 非 envelope + HTTP 错误(!res.ok)也必须抛,不留静默成功。
 */
async function parseBody<T>(res: Response): Promise<T> {
  const body = await res.json()
  if (body && typeof body === 'object' && typeof body.code === 'number') {
    if (body.code !== 0) {
      throw new ApiError(body.code, body.message ?? 'unknown error', res.status)
    }
    return body.data as T
  }
  if (!res.ok) {
    throw new ApiError(5001, `HTTP ${res.status}`, res.status)
  }
  return body as T
}

let refreshPromise: Promise<string | null> | null = null

/**
 * 单飞 refresh(spec §5 step 2 + SCAFFOLD-REF #14):
 * 并发 401 共享一次 refresh,避免 N 个请求各刷一次致 refresh token 失效。
 * refresh token 在 httpOnly cookie(path=/),credentials: 'include' 浏览器自动带。
 */
export async function refreshAccessToken(): Promise<string | null> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    try {
      const res = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        credentials: 'include',
      })
      if (!res.ok) return null
      const data = await parseBody<{ accessToken: string; expiresIn: number }>(res)
      if (data?.accessToken) {
        useAuthStore.getState().setAccessToken(data.accessToken)
        return data.accessToken
      }
      return null
    } catch {
      return null
    } finally {
      refreshPromise = null
    }
  })()
  return refreshPromise
}

export interface ApiFetchOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
  /** true = 跳过 401 自动 refresh(AI chat POST 非幂等,401 不重放) */
  skipAuthRetry?: boolean
}

/**
 * apiFetch — 带 Bearer + 401 单飞 refresh + 重试的 fetch 封装。
 *
 * @throws ApiError(code, message, status) — 调用方按 code 走 toast/跳转
 */
export async function apiFetch<T>(input: string, opts: ApiFetchOptions = {}): Promise<T> {
  const { body, skipAuthRetry, headers: headerInit, ...rest } = opts
  const headers = new Headers(headerInit)
  const token = useAuthStore.getState().accessToken
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const doFetch = () =>
    fetch(input, {
      ...rest,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      credentials: 'include',
    })

  const res = await doFetch()

  if (res.status === 401 && !skipAuthRetry) {
    const refreshed = await refreshAccessToken()
    if (refreshed) {
      headers.set('Authorization', `Bearer ${refreshed}`)
      const retry = await doFetch()
      return parseBody<T>(retry)
    }
    useAuthStore.getState().clearAuth()
    throw new ApiError(1001, '未认证,请重新登录', 401)
  }

  if (!res.ok && res.status !== 401) {
    // 非 401 错误仍走 parseBody 提取 ApiResponse code
    try {
      return await parseBody<T>(res)
    } catch (e) {
      if (e instanceof ApiError) throw e
      throw new ApiError(5001, `HTTP ${res.status}`, res.status)
    }
  }

  return parseBody<T>(res)
}

/**
 * authFetch — 带 Bearer + 401 单飞 refresh + 重试的 fetch 封装,返原始 Response。
 *
 * 与 apiFetch 区别:不 parseBody,留给调用方处理非 JSON 响应(文件流 CSV/JSON blob)。
 * exportTradeHistory 用此拿 Blob + Content-Disposition 文件名。
 */
export async function authFetch(
  input: string,
  opts: ApiFetchOptions = {},
): Promise<Response> {
  const { body, skipAuthRetry, headers: headerInit, ...rest } = opts
  const headers = new Headers(headerInit)
  const token = useAuthStore.getState().accessToken
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const doFetch = () =>
    fetch(input, {
      ...rest,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      credentials: 'include',
    })
  const res = await doFetch()
  if (res.status === 401 && !skipAuthRetry) {
    const refreshed = await refreshAccessToken()
    if (refreshed) {
      headers.set('Authorization', `Bearer ${refreshed}`)
      return doFetch()
    }
    useAuthStore.getState().clearAuth()
    throw new ApiError(1001, '未认证,请重新登录', 401)
  }
  return res
}
