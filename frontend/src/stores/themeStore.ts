import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * 主题偏好（persist 到 localStorage，key = kwikquant-theme）。
 *
 * colorScheme: 'dark' | 'light' — 映射到 <html class="dark">。
 * DESIGN.md §Colors 双主题映射：暗为默认皮肤（index.html 已挂 class="dark"）。
 *
 * 脚手架阶段只搭深浅色骨架；涨跌色 intl/cn（业务偏好）留到业务阶段。
 */
export type ColorScheme = 'dark' | 'light'

interface ThemeState {
  colorScheme: ColorScheme
  setColorScheme: (scheme: ColorScheme) => void
  toggleColorScheme: () => void
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      colorScheme: 'dark', // 默认暗主（Done AI 默认皮肤）

      setColorScheme: (scheme) => {
        set({ colorScheme: scheme })
        applyColorScheme(scheme)
      },

      toggleColorScheme: () => {
        const next = get().colorScheme === 'dark' ? 'light' : 'dark'
        set({ colorScheme: next })
        applyColorScheme(next)
      },
    }),
    {
      name: 'kwikquant-theme',
    },
  ),
)

/**
 * 将 colorScheme 应用到 <html> 元素。
 * 纯 DOM 操作、无 React 依赖，可随处调用（含 SSR 已由调用方保证 window 存在）。
 */
export function applyColorScheme(scheme: ColorScheme): void {
  const html = document.documentElement
  if (scheme === 'dark') {
    html.classList.add('dark')
  } else {
    html.classList.remove('dark')
  }
}

/**
 * App 启动时调用一次，把 persist 恢复的 state 应用到 DOM。
 * hydrate 前 <html> 已挂 class="dark"（index.html），本函数保证之后同步 store 状态。
 */
export function hydrateTheme(): void {
  applyColorScheme(useThemeStore.getState().colorScheme)
}
