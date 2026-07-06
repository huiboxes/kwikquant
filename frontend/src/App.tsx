import { useEffect } from 'react'
import { RouterProvider } from 'react-router-dom'
import { router } from '@/routes'
import { hydrateTheme } from '@/stores/themeStore'
import { useAuthStore } from '@/stores/authStore'
import { refreshAccessToken } from '@/lib/http'

export default function App() {
  // 主题 hydrate(persist 恢复 → DOM)
  useEffect(() => {
    hydrateTheme()
  }, [])

  // 认证 bootstrap:access token 不 persist(内存),刷新页面后用 refresh cookie(httpOnly)
  // 探活换新 access token。成功→authenticated,失败→anonymous(路由守卫跳 /login)。
  // 用 refreshAccessToken 单飞(http.ts singleflight):避免 React StrictMode dev 双调用
  // + refresh token 旋转致第二次 refresh 用旧 cookie 401 → clearAuth → 退出登录
  useEffect(() => {
    refreshAccessToken().then((token) => {
      if (!token) useAuthStore.getState().clearAuth()
      // token 存在时 refreshAccessToken 内部已 setAccessToken
    })
  }, [])

  return <RouterProvider router={router} />
}
