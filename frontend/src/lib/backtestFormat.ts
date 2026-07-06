import Decimal from 'decimal.js'
import { formatMoney as formatMoneyRaw } from './money'

/**
 * 回测指标格式化纯函数(spec §5 step 22)。
 *
 * 后端 MetricsDto:
 *   totalReturn(小数,0.1532) / sharpeRatio(1.85) / maxDrawdown(小数负值,-0.0842) /
 *   winRate(0-1,0.62) / profitFactor(2.1) / totalTrades(int32) / avgTradeDurationSeconds(int64)。
 *
 * 金额红线:比率/指标是 number(非金额字段),但格式化仍走 Decimal 避免 double 丢精度。
 *   不用 Number()/parseFloat(ESLint 硬拦),用 new Decimal(number) 构造。
 * DESIGN.md §data-row-mono:数字 系统等宽 font-mono + tnum,百分比/时长格式化在此完成。
 */

/** 小数(0.1532)→ 百分比字符串 "15.32%"。负值保留 -(maxDrawdown 用)。 */
export function formatPercent(ratio: number, dp = 2): string {
  const pct = new Decimal(ratio).times(100)
  return `${pct.toFixed(dp)}%`
}

/** 最大回撤(小数负值 -0.0842)→ "-8.42%"。复用 formatPercent(负值自然带 -)。 */
export function formatDrawdown(ratio: number, dp = 2): string {
  return formatPercent(ratio, dp)
}

/** 胜率(0-1,0.62)→ "62.00%"。 */
export function formatWinRate(rate: number, dp = 2): string {
  return formatPercent(rate, dp)
}

/** 夏普/盈亏比(1.85)→ "1.85"。 */
export function formatRatio(v: number, dp = 2): string {
  return new Decimal(v).toFixed(dp)
}

/** 平均持仓时长(秒)→ 人类可读 "1h 0m" / "2h 30m" / "45m 0s" / "30s"。 */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

/** 金额(string|number)→ 展示字符串。委托 money.formatMoney(千分位+固定小数)。 */
export function formatMoney(
  v: string | number | null | undefined,
  opts?: { dp?: number; sign?: boolean },
): string {
  if (v == null || v === '') return '—'
  try {
    const d = new Decimal(v)
    if (d.isNaN()) return '—'
    return formatMoneyRaw(d, opts)
  } catch {
    // 非法金额字符串(如 'abc')→ 不崩 UI,展示 '—'
    return '—'
  }
}
