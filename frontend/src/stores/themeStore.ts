import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * 主题偏好（persist 到 localStorage，key = kwikquant-theme）。
 *
 * colorScheme: 'dark' | 'light' — 映射到 <html class="dark">。
 * DESIGN.md §Colors 双主题映射：亮色为主、暗色备选（2026-07-07 用户拍板，暗色备选按原型图）。
 * index.html 默认不挂 class="dark"（亮色 fallback），hydrateTheme 同步 store 状态。
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
      colorScheme: 'light', // 亮色为主（2026-07-07 用户拍板：亮主暗备选，暗色备选按原型图）

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
 * hydrate 前 <html> 默认不挂 class="dark"（亮色 fallback），本函数同步 persist 的 store 状态到 DOM。
 */
export function hydrateTheme(): void {
  applyColorScheme(useThemeStore.getState().colorScheme)
}
