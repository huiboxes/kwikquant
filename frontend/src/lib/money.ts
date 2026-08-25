import Decimal from 'decimal.js'

/**
 * 金额唯一入口，集中金融红线。
 *
 * ⚠ 后端 BigDecimal 字段实际序列化为 **JSON number**(Jackson 默认 BigDecimal→number，后端无全局
 * write-bigdecimal-as-plain 也无 @JsonFormat(shape=STRING)），**非 string**。这是已知精度缺口
 * (JS number 精度 2^53，>该值丢精度),待后端改 BigDecimal→string 输出后入参改 string。
 * 现状:toDecimal 接 string|number 兼容，全程禁止 Number()/parseFloat 参与运算
 * （JS double 丢精度）。Decimal.toFixed 仅用于格式化输出，不参与运算。
 *
 * ESLint no-restricted-syntax 已硬拦 parseFloat/Number 调用（见 eslint.config.js）。
 */

/**
 * string|number → Decimal。空 / null → Decimal(0)（字段缺失安全降级）；
 * 非法字符串（如 'NaN'/'abc'）→ 抛错，不静默归零（金额字段数据质量问题必须暴露，不能掩盖）。
 *
 * 入参类型含 number 是因为后端 BigDecimal 实际序列化为 JSON number（见上方说明），
 * 运行时真 number，>2^53 会丢精度（待后端改 BigDecimal→string 输出）。
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
 * 渲染期派生场景的安全入口：与 toDecimal 同语义，但非法输入不抛、降级 Decimal(0)。
 *
 * 用途：交易页价格/数量输入在用户键入过程中会出现非法中间态（如 `77230.5-`），渲染期若用严格
 * toDecimal 会抛错冒泡到 React 错误边界 → 整页崩。此处兜底，仅用于展示派生（预估保证金/手续费等）。
 * 下单提交仍走严格 toDecimal，保证金额字段数据质量不被掩盖。
 */
export function tryToDecimal(v: string | number | null | undefined): Decimal {
  try {
    return toDecimal(v)
  } catch {
    return new Decimal(0)
  }
}

/**
 * 数值输入态过滤：只保留数字与单个小数点，剔除用户键入过程中的非法中间态。
 *
 * 价格/数量输入框 onChange 用它清洗后再 set state，从源头避免 `77230.5-` 这类非法串进 state。
 * 规则：保留 [0-9.]，多余的小数点只留首个，负号等其他符号一律去掉（价格/数量不允许负数）。
 * 不强制格式（允许 `77230.`、`.5` 等输入中间态），格式校验留给提交时的 toDecimal。
 */
export function sanitizeNumeric(s: string): string {
  // 先剔除非数字非小数点字符
  const cleaned = s.replace(/[^\d.]/g, '')
  // 多个小数点：保留首个点之前 + 首个点，第二个点起整段丢弃（用户误触第二个点，后续多为误输入）
  const firstDot = cleaned.indexOf('.')
  if (firstDot === -1) return cleaned
  const intPart = cleaned.slice(0, firstDot)
  // 首个点之后的小数部分：再遇点即截断
  const afterDot = cleaned.slice(firstDot + 1)
  const secondDot = afterDot.indexOf('.')
  const fracPart = secondDot === -1 ? afterDot : afterDot.slice(0, secondDot)
  return `${intPart}.${fracPart}`
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

/**
 * Decimal → 紧凑展示字符串(K/M)，用于侧栏收起态等窄空间，避免长数字溢出。
 * ≥1e6 → 'X.YM'(如 1,240,000 → '1.2M');≥1e3 → 'X.Yk'(如 124,556 → '124.6k')；否则整数。
 * 全程 Decimal 运算(abs/div/toFixed)，不碰 Number/parseFloat(金额红线)。
 * 除数 1_000_000 / 1000 是精确整数常量，无精度损失。
 */
export function formatMoneyCompact(v: Decimal, opts?: { sign?: boolean }): string {
  const negative = v.isNegative()
  const abs = v.abs()
  let body: string
  if (abs.gte(1_000_000)) {
    body = `${abs.div(1_000_000).toFixed(1)}M`
  } else if (abs.gte(1000)) {
    body = `${abs.div(1000).toFixed(1)}k`
  } else {
    body = abs.toFixed(0)
  }
  if (negative) return `-${body}`
  if (opts?.sign && !v.isZero()) return `+${body}`
  return body
}

/**
 * Decimal → 中文紧凑展示(亿/千万/万)，用于成交额等大数字(正规交易所中文习惯)。
 * ≥1e8 → 'X.XX亿'(750,000,000 → '7.5亿');≥1e7 → 'X.XX千万'(49,400,000 → '4.94千万');
 * ≥1e4 → 'X.XX万'(1,210,000 → '121万')；否则整数。trailing .00/.0 strip。
 * 全程 Decimal 运算，不碰 Number/parseFloat(金额红线)。
 */
export function formatMoneyCN(v: Decimal): string {
  const negative = v.isNegative()
  const abs = v.abs()
  let n: string
  let unit: string
  if (abs.gte(100_000_000)) {
    n = abs.div(100_000_000).toFixed(2)
    unit = '亿'
  } else if (abs.gte(10_000_000)) {
    n = abs.div(10_000_000).toFixed(2)
    unit = '千万'
  } else if (abs.gte(10_000)) {
    n = abs.div(10_000).toFixed(2)
    unit = '万'
  } else {
    return (negative ? '-' : '') + abs.toFixed(0)
  }
  n = n.replace(/\.?0+$/, '') // "7.50" → "7.5", "121.00" → "121"
  return (negative ? '-' : '') + n + unit
}
