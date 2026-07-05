import { useEffect } from 'react'
import { RouterProvider } from 'react-router-dom'
import { router } from '@/routes'
import { hydrateTheme } from '@/stores/themeStore'
import { useAuthStore } from '@/stores/authStore'
import { apiFetch } from '@/lib/http'

export default function App() {
  // 主题 hydrate(persist 恢复 → DOM)
  useEffect(() => {
    hydrateTheme()
  }, [])

  // 认证 bootstrap:access token 不 persist(内存),刷新页面后用 refresh cookie(httpOnly)
  // 探活换新 access token。成功→authenticated,失败→anonymous(路由守卫跳 /login)。
  useEffect(() => {
    apiFetch<{ accessToken: string; expiresIn: number }>('/api/v1/auth/refresh', {
      method: 'POST',
      skipAuthRetry: true,
    })
      .then((data) => {
        if (data?.accessToken) useAuthStore.getState().setAccessToken(data.accessToken)
        else useAuthStore.getState().clearAuth()
      })
      .catch(() => useAuthStore.getState().clearAuth())
  }, [])

  return <RouterProvider router={router} />
}
