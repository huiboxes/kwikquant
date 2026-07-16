import { useEffect } from 'react'
import { RouterProvider } from 'react-router-dom'
import { router } from '@/routes'
import { hydrateTheme } from '@/stores/themeStore'
import { useAuthStore } from '@/stores/authStore'
import { refreshAccessToken } from '@/lib/http'

/** AT 剩余有效期阈值(秒)。低于此值时 preemptive refresh,高于则跳过。 */
const REFRESH_THRESHOLD_SEC = 60

export default function App() {
  // 主题 hydrate(persist 恢复 → DOM)
  useEffect(() => {
    hydrateTheme()
  }, [])

  // 认证 bootstrap:
  // 1. 检查 sessionStorage 中缓存的 AT + 过期时间
  // 2. 如果 AT 存在且剩余有效期 > 阈值 → 直接用,跳过 refresh
  // 3. 否则调 refreshAccessToken 拿新 AT(单飞,防 StrictMode 双调)
  useEffect(() => {
    const cached = sessionStorage.getItem('kwikquant.at')
    const cachedExp = Number(sessionStorage.getItem('kwikquant.at.exp') || '0')
    const nowSec = Math.floor(Date.now() / 1000)

    if (cached && cachedExp - nowSec > REFRESH_THRESHOLD_SEC) {
      // AT 仍有效且余量充足,直接用,不调 refresh
      useAuthStore.getState().setAccessToken(cached)
      return
    }

    // AT 丢失/过期/即将过期 → preemptive refresh
    refreshAccessToken().then((token) => {
      if (!token) {
        useAuthStore.getState().clearAuth()
      }
      // token 存在时 refreshAccessToken 内部已 setAccessToken + 写 sessionStorage
    })
  }, [])

  return <RouterProvider router={router} />
}
