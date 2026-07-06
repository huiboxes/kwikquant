import { Skeleton } from '@/components/ui/skeleton'

/**
 * LoadingState — 加载态骨架屏(spec §5 #4 四态之一)。
 *
 * 页面级 data loading + 路由 Suspense fallback 用骨架屏(无 spinner 文案,现代 UX);
 * label 转 aria-label(屏幕阅读器可读,视觉无"加载中"文案)。
 * 按钮/短加载用 Button disabled + 文案(如"发布中…"),不用此组件。
 */
export function LoadingState({
  label = '加载中',
  rows = 3,
  className = '',
}: {
  label?: string
  /** 骨架屏行数(默认 3) */
  rows?: number
  className?: string
}) {
  return (
    <div
      className={`space-y-md p-lg ${className}`}
      role="status"
      aria-label={label}
      aria-live="polite"
    >
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="h-[40px] w-full" />
      ))}
    </div>
  )
}
