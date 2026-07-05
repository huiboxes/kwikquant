/**
 * LoadingState — 加载态占位(spec §5 #4 四态之一,批 1a step 5 完善为 Skeleton 变体)。
 * 路由级 Suspense fallback 用此组件。
 */
export function LoadingState({ label = '加载中…' }: { label?: string }) {
  return (
    <div
      className="flex h-full min-h-[240px] items-center justify-center text-text-muted"
      role="status"
      aria-live="polite"
    >
      <div className="flex items-center gap-sm">
        <span
          className="h-[16px] w-[16px] animate-spin rounded-full border-2 border-current border-t-transparent"
          aria-hidden
        />
        <span className="font-body text-body-sm">{label}</span>
      </div>
    </div>
  )
}
