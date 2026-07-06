/**
 * toUnixSeconds — Instant ISO-8601 字符串 → Unix 秒数(UTCTimestamp)。
 *
 * lightweight-charts v5 拒收 ISO 字符串(intraday 必须 UTCTimestamp 秒数),
 * EquityPointDto.time(Instant 序列化为 ISO-8601,如 "2026-06-15T08:30:00Z")需转换。
 *
 * 纯函数,无副作用,便于单测(spec §5 step 21 验证:
 *   "2026-06-15T08:30:00Z" → 1750254600)。
 */
export function toUnixSeconds(iso: string): number {
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) {
    throw new Error(`toUnixSeconds: invalid ISO date string: '${iso}'`)
  }
  return Math.floor(ms / 1000)
}
