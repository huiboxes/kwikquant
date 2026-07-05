import { create } from 'zustand'
import { decodeJwt, isExpired, type JwtPayload } from '@/lib/jwt'

/**
 * 认证状态机(spec §5 step 2 + SCAFFOLD-REF #17)。
 *
 * 三态:unknown(启动) → authenticated(login 成功 setAccessToken) / anonymous(401 失败 clearAuth)。
 *
 * **access token 存内存**(Zustand 非 persist),不落 localStorage — 防 XSS 偷 token。
 * refresh token 走 httpOnly cookie(path=/),浏览器自动附带,前端 JS 不可读。
 *
 * user 身份从 access token payload 派生(decodeJwt),无 GET /auth/me 端点。
 */

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous'

export interface AuthUser {
  userId: number
  username: string
}

interface AuthState {
  status: AuthStatus
  accessToken: string | null
  user: AuthUser | null
  /** login/refresh 成功后调;decode token + 派生 user + 状态→authenticated */
  setAccessToken: (token: string) => void
  /** 401 refresh 失败 / logout 调;状态→anonymous */
  clearAuth: () => void
  /** 启动时探活:有 token 且未过期 → authenticated,否则 anonymous */
  hydrate: () => void
}

export const useAuthStore = create<AuthState>((set, get) => ({
  status: 'unknown',
  accessToken: null,
  user: null,

  setAccessToken: (token) => {
    try {
      const payload: JwtPayload = decodeJwt(token)
      if (isExpired(payload.exp)) {
        set({ status: 'anonymous', accessToken: null, user: null })
        return
      }
      set({
        status: 'authenticated',
        accessToken: token,
        user: { userId: payload.userId, username: payload.username },
      })
    } catch {
      set({ status: 'anonymous', accessToken: null, user: null })
    }
  },

  clearAuth: () => set({ status: 'anonymous', accessToken: null, user: null }),

  hydrate: () => {
    const token = get().accessToken
    if (!token) {
      set({ status: 'anonymous' })
      return
    }
    try {
      const payload = decodeJwt(token)
      if (isExpired(payload.exp)) {
        set({ status: 'anonymous', accessToken: null, user: null })
      } else {
        set({
          status: 'authenticated',
          user: { userId: payload.userId, username: payload.username },
        })
      }
    } catch {
      set({ status: 'anonymous', accessToken: null, user: null })
    }
  },
}))
