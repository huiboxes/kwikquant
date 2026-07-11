import markDark from '@/assets/kwikquant-mark.svg'
import markLight from '@/assets/kwikquant-mark-light.svg'
import { useThemeStore } from '@/stores/themeStore'

/**
 * 品牌 mark——信号线渐变 logo。
 * 深色模式用 kwikquant-mark.svg(白→蓝,暗底显),浅色用 mark-light.svg(黑→蓝,亮底显)。
 * 按 themeStore.colorScheme 切换(不依赖 Tailwind dark: variant,直接 React 驱动)。
 */
export function BrandMark({ className }: { className?: string }) {
  const colorScheme = useThemeStore((s) => s.colorScheme)
  return (
    <img
      src={colorScheme === 'dark' ? markDark : markLight}
      className={className}
      alt="KwikQuant"
    />
  )
}
