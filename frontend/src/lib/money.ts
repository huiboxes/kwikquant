import Decimal from 'decimal.js'

/**
 * 金额唯一入口，集中金融红线。
 *
 * 后端金额字段（BigDecimal）序列化为带引号 string；这里解析为 Decimal，全程禁止
 * Number()/parseFloat 参与运算（JS double 丢精度）。Decimal.toFixed 仅用于格式化输出，不参与运算。
 *
 * ESLint no-restricted-syntax 已硬拦 parseFloat/Number 调用（见 eslint.config.js）。
 */

/**
 * string|number → Decimal。空 / null → Decimal(0)（字段缺失安全降级）；
 * 非法字符串（如 'NaN'/'abc'）→ 抛错，不静默归零（金额字段数据质量问题必须暴露，不能掩盖）。
 *
 * 入参类型含 number 仅为兼容 OpenAPI 契约把后端 BigDecimal 标注成 number（springdoc 局限）——
 * 后端实际全局序列化为带引号 string，运行时传入的是 string，精度无损。
 */
export function toDecimal(v: string | number | null | undefined): Decimal {
  if (v == null || v === '') {
    console.warn('[money] toDecimal received empty value, defaulting to 0')
    return new Decimal(0)
  }
  const d = new Decimal(v)
  // decimal.js 对 'NaN' 字符串不抛错、返回 NaN 状态的 Decimal（'abc' 才会在 new Decimal 抛）。
  // 金融红线：数据质量问题必须暴露，不能静默归零 → 显式检查后抛。
  if (d.isNaN()) throw new Error(`toDecimal: illegal NaN input: '${String(v)}'`)
  return d
}

/**
 * Decimal → 展示字符串：千分位 + 固定小数位（默认 2）。tabular-nums 由 className 保证。
 *
 * @param opts.dp 小数位数，默认 2
 * @param opts.sign 是否对正数前置 +（盈亏场景），默认 false；负数始终带 -；零不加 +
 */
export function formatMoney(v: Decimal, opts?: { dp?: number; sign?: boolean }): string {
  const dp = opts?.dp ?? 2
  const fixed = v.toFixed(dp)
  const negative = fixed.startsWith('-')
  const abs = negative ? fixed.slice(1) : fixed
  const [intPart, fracPart] = abs.split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const body = fracPart != null ? `${grouped}.${fracPart}` : grouped
  if (negative) return `-${body}`
  if (opts?.sign && !v.isZero()) return `+${body}`
  return body
}
