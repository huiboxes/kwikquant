import { AlertTriangle, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'

/**
 * ErrorState — 错误态占位(spec §5 共享组件 #6,接 Query 错误)。
 *
 * 接 ApiError(从 react-query mutation/query 错误抛出),显示 code + message,
 * 提供重试按钮(调 refetch/retry)。用于 query 失败的页面级 fallback。
 *
 * DESIGN.md token: surface-card 底 + down(红)图标 + text-primary 标题 + text-secondary 描述。
 */
export interface ErrorStateProps {
  /** 错误标题,默认"加载失败" */
  title?: string
  /** 错误描述(从 ApiError.message 取) */
  message?: string
  /** 错误码(从 ApiError.code 取,显示在描述后) */
  code?: number | string
  /** 重试回调,提供则显示重试按钮 */
  onRetry?: () => void
  retryLabel?: string
}

export function ErrorState({
  title = '加载失败',
  message,
  code,
  onRetry,
  retryLabel = '重试',
}: ErrorStateProps) {
  return (
    <div
      className="flex min-h-[240px] flex-col items-center justify-center gap-md rounded-lg bg-surface-card p-2xl text-center"
      role="alert"
      aria-live="assertive"
    >
      <AlertTriangle className="size-8 text-down" aria-hidden />
      <div className="space-y-sm">
        <p className="font-body text-body text-text-primary">{title}</p>
        {message && (
          <p className="font-body text-body-sm text-text-secondary">
            {message}
            {code !== undefined && code !== null && (
              <span className="ml-sm font-mono-num text-caption text-text-muted">
                (code {code})
              </span>
            )}
          </p>
        )}
      </div>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RefreshCw className="size-4" aria-hidden />
          {retryLabel}
        </Button>
      )}
    </div>
  )
}
