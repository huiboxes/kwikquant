import { Sun, Moon } from 'lucide-react'
import { useThemeStore } from '@/stores/themeStore'

/**
 * ThemeToggle — 暗主/亮备选主题切换(spec §5 step 3)。
 *
 * 接 themeStore.toggleColorScheme。图标随当前 scheme 切换:
 * 暗主时显示 Sun(可切到亮),亮备选时显示 Moon(可切回暗)。
 *
 * DESIGN.md token: text-text-secondary / hover bg-surface-hover / hover text-text-primary。
 * 与 SidebarRail 导航按钮同尺寸(h-[40px] w-[40px])保持视觉一致。
 */
export function ThemeToggle() {
  const { colorScheme, toggleColorScheme } = useThemeStore()
  const isDark = colorScheme === 'dark'
  const Icon = isDark ? Sun : Moon
  const label = isDark ? '切换到亮色主题' : '切换到暗色主题'

  return (
    <button
      type="button"
      onClick={toggleColorScheme}
      className="flex h-[40px] w-[40px] items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft"
      aria-label={label}
      title={label}
    >
      <Icon className="h-[20px] w-[20px]" />
    </button>
  )
}
