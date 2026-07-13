import { chgArrow, chgTone } from './format'

/**
 * 盈亏展示 helper:涨跌色 + 箭头 + 文本标签(a11y WCAG 2.2 AA)。
 *
 * CLAUDE.md §金额红线:涨跌不靠颜色单独表达,必须配 ↑↓ 箭头 + 文本标签。
 * 金额值本身走 decimal.js(`./money`);此处只决定展示语义(tone / 箭头 / 标签 / class),
 * 不参与金额运算,入参用 number(由 Decimal.toNumber() 在调用方转,仅用于展示判断)。
 */

export type PnlTone = 'up' | 'down' | 'neutral'

/** 盈亏 tone,复用 chgTone(盈利=up / 亏损=down / 持平=neutral)。 */
export function pnlTone(v: number): PnlTone {
  return chgTone(v)
}

/** 盈亏箭头(▲/▼/·),复用 chgArrow。 */
export function pnlArrow(v: number): string {
  return chgArrow(v)
}

/**
 * 盈亏 a11y 文本标签。配 sr-only 或可见标签用,让屏幕阅读器/色弱用户能识别方向。
 * 持仓盈亏用"盈利/亏损";价格变动用 chgTone + chgArrow 即可(价格场景用"上涨/下跌"在调用方硬编码)。
 */
export function pnlLabel(v: number): string {
  if (v > 0) return '盈利'
  if (v < 0) return '亏损'
  return '持平'
}

/** tone → tailwind text class(DESIGN.md colors:up/down/muted)。给动态染色的金额/数字展示用。 */
export function pnlTextClass(v: number): string {
  const t = pnlTone(v)
  if (t === 'up') return 'text-up'
  if (t === 'down') return 'text-down'
  return 'text-text-muted'
}
