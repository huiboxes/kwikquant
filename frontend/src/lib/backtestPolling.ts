/**
 * 回测轮询退避纯函数(spec §5 step 20 + behavior-contract.md §3)。
 *
 * 间隔序列:2s/2s/4s/8s/10s(上限 10s,behavior-contract.md:130 指数退避)。
 * status === 'COMPLETED' | 'FAILED' → 停止(返 false)。
 * 其余:返序列中第 attempt 个间隔(ms),超长后返 CAP。
 *
 * 不超时(契约 C:回测跑几分钟,前端不主动放弃,轮询持续到终态)。
 *
 * 纯函数,不依赖 react-query,便于单测。
 */

export type BacktestStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

/** 退避序列(下标 = 已成功 fetch 次数 - 1,0-based)。上限 CAP_MS。 */
const INTERVALS_MS = [2_000, 2_000, 4_000, 8_000, 10_000] as const
const CAP_MS = 10_000

/**
 * @param status 当前任务状态(undefined=尚未拿到数据,停止)
 * @param attempt 已成功 fetch 次数 - 1(0-based;react-query dataUpdateCount - 1)
 * @returns 下次轮询间隔 ms,false=停止轮询
 */
export function nextBacktestInterval(
  status: BacktestStatus | undefined,
  attempt: number,
): number | false {
  if (status === undefined) return false
  if (status === 'COMPLETED' || status === 'FAILED') return false
  const idx = Math.max(0, attempt)
  if (idx >= INTERVALS_MS.length) return CAP_MS
  return INTERVALS_MS[idx]
}
