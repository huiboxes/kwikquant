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
  if (parsed === null) {
    throw new ApiError(0, '空响应(非 JSON)', res.status)
  }
  return parsed.data as T
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
