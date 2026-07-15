import { flushSync } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { apiFetch } from '@/lib/http'
import { useAuthStore } from '@/stores/authStore'
import { getWsConnection } from '@/lib/ws/ConnectionManager'

/**
 * useLogout — 退出登录。
 *
 * logout 请求 skipAuthRetry: true(防 clearAuth 后 401 自动 refresh → setAccessToken → 闪回);
 * flushSync 同步 disconnect WS + clearAuth + navigate('/login')(SPA 不 reload,refresh 不重跑)。
 */
export function useLogout() {
  const navigate = useNavigate()
  return () => {
    apiFetch<void>('/api/v1/auth/logout', {
      method: 'POST',
      skipAuthRetry: true,
    }).catch(() => {})
    flushSync(() => {
      getWsConnection().disconnect()
      useAuthStore.getState().clearAuth()
      navigate('/login')
    })
  }
}
