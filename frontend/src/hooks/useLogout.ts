import { useNavigate } from 'react-router-dom'
import { apiFetch } from '@/lib/http'
import { useAuthStore } from '@/stores/authStore'

/**
 * useLogout — 退出登录(fire-and-forget,不阻塞 UI)。
 *
 * 立即 clearAuth + 跳 /login(用户瞬间感知退出,无加载中);
 * 异步发 POST /auth/logout 吊销 refresh token + 清 cookie(失败静默,后端 401 等不阻塞)。
 */
export function useLogout() {
  const navigate = useNavigate()
  return () => {
    useAuthStore.getState().clearAuth()
    navigate('/login')
    // 异步发请求,不 await,不显示加载态
    apiFetch<void>('/api/v1/auth/logout', { method: 'POST' }).catch(() => {})
  }
}
