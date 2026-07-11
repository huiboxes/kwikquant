import { format } from 'date-fns'

/**
 * 通用展示格式化纯函数(非金额)。
 *
 * 金额走 `./money`(decimal.js,金融红线);此处只处理比率/日期/数字展示,
 * 不参与金额运算,因此用原生 number 无精度风险。
 */

/**
 * 涨跌方向 tone,给 Chip color / 组件 className 用。
 * DESIGN.md §colors:up=Babu / down=Signal Down。零值归 neutral(色不单独表达 a11y)。
 */
export function chgTone(v: number): 'up' | 'down' | 'neutral' {
  if (v > 0) return 'up'
  if (v < 0) return 'down'
  return 'neutral'
}

/**
 * 涨跌箭头字符。a11y WCAG 2.2 AA:涨跌不靠颜色单独表达,配箭头 + 文本标签(见 `./pnl`)。
 * 零值用 '·'(中性点)。
 */
export function chgArrow(v: number): '▲' | '▼' | '·' {
  if (v > 0) return '▲'
  if (v < 0) return '▼'
  return '·'
}

/**
 * 百分比格式化:固定小数位 + 可选正号。v 为已换算的百分数(2.34 表示 2.34%)。
 * 用于非金额比率(chg / winRate / 持仓占比);金额类百分比仍走 decimal.js(`formatMoney`)。
 */
export function formatPercent(v: number, opts?: { dp?: number; sign?: boolean }): string {
  const dp = opts?.dp ?? 2
  const fixed = v.toFixed(dp)
  const negative = fixed.startsWith('-')
  const abs = negative ? fixed.slice(1) : fixed
  if (negative) return `-${abs}%`
  if (opts?.sign && v !== 0) return `+${abs}%`
  return `${abs}%`
}

/**
 * 普通数字千分位格式化(非金额)。如 1284 → '1,284',3.1415 → '3.14'。
 * 金额一律走 `./money`(decimal.js),此函数仅用于交易笔数/行数等非金融量。
 */
export function formatNumber(v: number, dp: number = 0): string {
  const fixed = v.toFixed(dp)
  const negative = fixed.startsWith('-')
  const abs = negative ? fixed.slice(1) : fixed
  const [intPart, fracPart] = abs.split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const body = fracPart != null ? `${grouped}.${fracPart}` : grouped
  return negative ? `-${body}` : body
}

/**
 * ISO 日期字符串 → 展示字符串(date-fns)。默认 'yyyy-MM-dd HH:mm'。
 * 非法日期返回 '-',不抛错(展示层容错)。
 */
export function formatDateTime(iso: string, fmt: string = 'yyyy-MM-dd HH:mm'): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  return format(d, fmt)
}

/**
 * ISO 日期 → 仅日期 'yyyy-MM-dd'。
 */
export function formatDate(iso: string): string {
  return formatDateTime(iso, 'yyyy-MM-dd')
}
