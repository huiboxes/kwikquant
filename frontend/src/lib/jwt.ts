/**
 * JWT decode 纯函数(spec §5 step 2)。
 *
 * 后端 JwtProvider:access token = subject(userId) + claim("username") + exp(HS256 签)。
 * 前端 **不验签**(HS256 对称密钥不上前端),只 decode payload 取 userId/username/exp。
 * 无 GET /auth/me 端点 — 用户身份从 access token payload 派生。
 *
 * 金额红线注意:userId 是数字 ID(非金额),parseInt 安全;parseFloat/Number 不参与金额运算。
 */

export class InvalidTokenError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'InvalidTokenError'
  }
}

export interface JwtPayload {
  /** 用户 ID(从 sub 字段 parseLong,数字 ID 非金额) */
  userId: number
  username: string
  /** 过期时间,Unix 秒 */
  exp: number
  /** 签发时间,Unix 秒(可选) */
  iat?: number
  /** token ID(可选,用于撤销) */
  jti?: string
}

/**
 * Decode JWT payload(不验签)。非法格式抛 InvalidTokenError。
 */
export function decodeJwt(token: string): JwtPayload {
  const parts = token.split('.')
  if (parts.length !== 3) {
    throw new InvalidTokenError('JWT must have 3 parts separated by "."')
  }
  let payload: Record<string, unknown>
  try {
    payload = JSON.parse(base64UrlDecode(parts[1])) as Record<string, unknown>
  } catch (e) {
    throw new InvalidTokenError(`payload is not valid JSON: ${(e as Error).message}`)
  }

  const sub = payload.sub
  const username = payload.username
  const exp = payload.exp

  if (typeof sub !== 'string' && typeof sub !== 'number') {
    throw new InvalidTokenError('missing or invalid "sub" claim')
  }
  if (typeof username !== 'string') {
    throw new InvalidTokenError('missing or invalid "username" claim')
  }
  if (typeof exp !== 'number' || !Number.isFinite(exp)) {
    throw new InvalidTokenError('missing or invalid "exp" claim')
  }

  const userId = typeof sub === 'number' ? sub : parseInt(sub, 10)
  if (!Number.isInteger(userId) || userId <= 0) {
    throw new InvalidTokenError(`invalid userId: ${sub}`)
  }

  return {
    userId,
    username,
    exp,
    iat: typeof payload.iat === 'number' ? payload.iat : undefined,
    jti: typeof payload.jti === 'string' ? payload.jti : undefined,
  }
}

/**
 * 判断 token 是否已过期。nowMs 默认 Date.now()。
 * 用真实 exp(无提前缓冲):setAccessToken/hydrate 判断 token 当前是否有效。
 * 提前 refresh 由 401 拦截器触发,不在此处预判。
 */
export function isExpired(exp: number, nowMs: number = Date.now()): boolean {
  return nowMs >= exp * 1000
}

/** base64url decode → UTF-8 字符串 */
function base64UrlDecode(input: string): string {
  // base64url → base64:替换字符 + 补 padding
  let b64 = input.replace(/-/g, '+').replace(/_/g, '/')
  const pad = b64.length % 4
  if (pad) b64 += '='.repeat(4 - pad)
  const binary = atob(b64)
  // binary string → UTF-8 bytes → text(防多字节字符乱码)
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}
