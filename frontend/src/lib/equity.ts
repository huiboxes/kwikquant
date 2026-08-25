import Decimal from 'decimal.js'

export type EquityPoint = [number, number]

/**
 * 权益曲线是否有绘制意义。
 *
 * 无波动不画：后端无成交时回全零占位点，有资金未动时回全平的初始值线，
 * 两种平线直接画都是一块贴中的渐变色块加一串重复刻度，观感像坏图；
 * 资产数额本身有头部数字表达，曲线只负责讲"变动"的故事。
 * 调用方据此降级为占位文案。点数不足 2 也无法成线。
 */
export function hasMeaningfulCurve(points: EquityPoint[]): boolean {
  if (points.length < 2) return false
  const values = points.map((p) => p[1])
  return values.some((v) => v !== values[0])
}

/**
 * 曲线线色按首末变动方向取涨跌语义色：赚涨色、亏跌色、持平涨色。
 * 比较走 decimal.js，避免浮点误差把小额盈亏判反。
 */
export function equityColor(points: EquityPoint[]): string {
  const first = points[0]?.[1] ?? 0
  const last = points[points.length - 1]?.[1] ?? 0
  return new Decimal(last).minus(first).gte(0) ? 'var(--up)' : 'var(--down)'
}
