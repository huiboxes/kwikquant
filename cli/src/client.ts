import type { Credentials } from './config.js'

/**
 * 后端 REST 响应信封(对应 Java ApiResponse<T>:{ code, message, data })。
 * 成功时 data 有值;失败时 code 非 0 + message 描述。
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
}

export class ApiError extends Error {
  constructor(public code: number, message: string, public status: number) {
    super(`[${code}] ${message}`)
    this.name = 'ApiError'
  }
}

/**
 * 校验信封:HTTP 2xx 但 code≠0(业务失败,如风控拒单 4105)也必须抛错——
 * 否则 CLI 把风控拒绝显示为"下单成功"(exit 0),agent 误判订单已存在。
 * 纯函数(便于单测):不依赖 fetch,只做 envelope 语义判定。
 */
export function assertEnvelope<T>(parsed: ApiResponse<T> | null, httpStatus: number): T {
  if (parsed === null) {
    throw new ApiError(0, '空响应(非 JSON)', httpStatus)
  }
  if (parsed.code !== 0) {
    // HTTP 200 + code≠0 = 业务失败(风控拒绝/配额超限/状态冲突等),按 code 抛
    throw new ApiError(parsed.code, parsed.message, httpStatus)
  }
  return parsed.data as T
}

async function handleResponse<T>(res: Response): Promise<T> {
  const text = await res.text()
  let parsed: ApiResponse<T> | null = null
  if (text) {
    try {
      parsed = JSON.parse(text) as ApiResponse<T>
    } catch {
      parsed = null
    }
  }
  if (!res.ok) {
    const code = parsed?.code ?? res.status
    const msg = parsed?.message ?? `HTTP ${res.status}`
    throw new ApiError(code, msg, res.status)
  }
  // HTTP 2xx:仍需校验 envelope code(业务失败不靠 HTTP 状态码表达)
  return assertEnvelope(parsed, res.status)
}

export async function apiGet<T>(creds: Credentials, path: string): Promise<T> {
  const res = await fetch(`${creds.baseUrl}${path}`, {
    headers: { Authorization: `Bearer ${creds.jwt}` },
  })
  return handleResponse<T>(res)
}

export async function apiPost<T>(creds: Credentials, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${creds.baseUrl}${path}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${creds.jwt}`,
      'Content-Type': 'application/json',
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  return handleResponse<T>(res)
}

export async function apiDelete<T>(creds: Credentials, path: string): Promise<T> {
  const res = await fetch(`${creds.baseUrl}${path}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${creds.jwt}` },
  })
  return handleResponse<T>(res)
}
