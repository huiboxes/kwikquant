import { useEffect } from 'react'
import { Dashboard } from '@/pages/Dashboard'
import { hydrateTheme } from '@/stores/themeStore'

export default function App() {
  // 从 persist 恢复主题状态到 DOM(index.html 初始 class="dark"，
  // 若用户上次选了 light，本 effect 同步 DOM。业务阶段可加 flash-of-wrong-theme 兜底。)
  useEffect(() => {
    hydrateTheme()
  }, [])

  return <Dashboard />
}
