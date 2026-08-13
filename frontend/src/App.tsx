import { useEffect } from 'react'
import { RouterProvider } from 'react-router-dom'
import { router } from '@/routes'
import { hydrateTheme } from '@/stores/themeStore'
import { refreshAccessToken } from '@/lib/http'

export default function App() {
  // 主题 hydrate(persist 恢复 → DOM)
  useEffect(() => {
    hydrateTheme()
  }, [])

  // 认证 bootstrap 只依赖 httpOnly refresh cookie；AT 始终仅驻留内存。
  useEffect(() => {
    void refreshAccessToken()
  }, [])

  return <RouterProvider router={router} />
}
