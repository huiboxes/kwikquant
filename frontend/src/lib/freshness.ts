/**
 * 数据新鲜度判定纯函数。
 *
 * 快照类数据（账户/持仓/PnL）以 snapshotAt 为锚：超过阈值即视为过期，
 * 由 FreshnessBadge 组件（业务阶段实现）转 warn 态提示"数据可能过期"。
 * now 参数便于测试注入，默认当前时刻。
 */

const DEFAULT_THRESHOLD_SEC = 30

/**
 * snapshotAt 距 now 超过 thresholdSec（默认 30s）→ 过期。
 * 未来时间（时钟偏移）视为新鲜（返回 false）。
 */
export function isStale(
  snapshotAt: Date,
  thresholdSec: number = DEFAULT_THRESHOLD_SEC,
  now: Date = new Date(),
): boolean {
  const ageMs = now.getTime() - snapshotAt.getTime()
  return ageMs > thresholdSec * 1000
}

/** 相对时间标签，如 "刚刚" / "3 秒前" / "5 分钟前" / "2 小时前"。未来时间归为 "刚刚"。 */
export function freshnessLabel(snapshotAt: Date, now: Date = new Date()): string {
  const ageSec = Math.floor((now.getTime() - snapshotAt.getTime()) / 1000)
  if (ageSec < 5) return '刚刚'
  if (ageSec < 60) return `${ageSec} 秒前`
  const ageMin = Math.floor(ageSec / 60)
  if (ageMin < 60) return `${ageMin} 分钟前`
  const ageHour = Math.floor(ageMin / 60)
  if (ageHour < 24) return `${ageHour} 小时前`
  const ageDay = Math.floor(ageHour / 24)
  return `${ageDay} 天前`
}
