import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * 主题偏好（persist 到 localStorage，key = kwikquant-theme）。
 *
 * colorScheme: 'dark' | 'light' — 映射到 <html class="dark">。
 * DESIGN.md Colors 双主题映射：亮为默认皮肤（index.html 默认不挂 dark class）。
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
      colorScheme: 'light', // 默认亮主

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
 * 支持 View Transitions 的浏览器走 200ms 交叉过渡(整页主题不硬切);
 * 不支持或 prefers-reduced-motion 时直切。
 */
export function applyColorScheme(scheme: ColorScheme): void {
  const apply = () => {
    const html = document.documentElement
    if (scheme === 'dark') {
      html.classList.add('dark')
    } else {
      html.classList.remove('dark')
    }
  }
  const doc = document as Document & {
    startViewTransition?: (cb: () => void) => unknown
  }
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (!reduced && typeof doc.startViewTransition === 'function') {
    doc.startViewTransition(apply)
    return
  }
  apply()
}

/**
 * App 启动时调用一次，把 persist 恢复的 state 应用到 DOM。
 * hydrate 前 <html> 默认无 dark class（index.html 亮基），本函数保证之后同步 store 状态。
 */
export function hydrateTheme(): void {
  applyColorScheme(useThemeStore.getState().colorScheme)
}
