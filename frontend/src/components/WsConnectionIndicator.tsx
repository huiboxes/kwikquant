import { Wifi, WifiOff, Loader2 } from 'lucide-react'
import { useWsStore, type WsStatus } from '@/stores/wsStore'

/**
 * WsConnectionIndicator — WS 连接状态指示器(spec §5 step 9,F12.6)。
 *
 * 三态:🟢 connected / 🟡 reconnecting(connecting) / 🔴 failed(idle 未连或重连耗尽)。
 * Tooltip:上次连接时间 + 重连次数 + 最近错误。
 * 断连 Banner:status=failed 时全屏顶部黄色横幅。
 *
 * DESIGN.md token:bg-up(绿)/bg-warning(黄)/bg-down(红) 圆点 + text-* 文字。
 */
interface StatusConfig {
  dotClass: string
  Icon: typeof Wifi
  label: string
  spin: boolean
}

function statusConfig(status: WsStatus): StatusConfig {
  switch (status) {
    case 'connected':
      return { dotClass: 'text-up', Icon: Wifi, label: '已连接', spin: false }
    case 'connecting':
    case 'reconnecting':
      return { dotClass: 'text-warning', Icon: Loader2, label: '重连中', spin: true }
    case 'failed':
      return { dotClass: 'text-down', Icon: WifiOff, label: '已断开', spin: false }
    case 'idle':
    default:
      return { dotClass: 'text-text-muted', Icon: WifiOff, label: '未连接', spin: false }
  }
}

function formatTime(ts: number | null): string {
  if (!ts) return '无'
  return new Date(ts).toLocaleTimeString('zh-CN')
}

export function WsConnectionIndicator() {
  const { status, attempt, lastConnectedAt, lastError } = useWsStore()
  const cfg = statusConfig(status)

  const tooltip = [
    `状态: ${cfg.label}`,
    `上次连接: ${formatTime(lastConnectedAt)}`,
    `重连次数: ${attempt}`,
    lastError ? `最近错误: ${lastError}` : null,
  ]
    .filter(Boolean)
    .join('\n')

  return (
    <>
      {/* 断连 Banner:failed 时全屏顶部黄色横幅 */}
      {status === 'failed' && (
        <div
          className="fixed inset-x-0 top-0 z-50 border-b border-border-soft bg-surface-card px-xl py-sm text-center font-body text-body-sm text-text-secondary"
          role="alert"
        >
          ⚠ 实时连接已断开，请检查网络后刷新页面
        </div>
      )}
      {/* 指示器 */}
      <span
        className="inline-flex items-center gap-1 rounded-full px-sm py-1 text-caption text-text-secondary"
        title={tooltip}
        aria-label={`WebSocket ${cfg.label}`}
      >
        <span className={cfg.dotClass} aria-hidden>
          <cfg.Icon
            className={`size-3 ${cfg.spin ? 'animate-spin' : ''}`}
            strokeWidth={3}
          />
        </span>
        <span className="font-body">{cfg.label}</span>
      </span>
    </>
  )
}
