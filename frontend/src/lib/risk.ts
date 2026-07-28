import { toDecimal, formatMoney } from '@/lib/money'

/**
 * 风控规则展示辅助。
 *
 * 金额红线:params 里的 maxNotionalUsdt/maxLossUsdt 走 decimal.js(toDecimal + formatMoney),
 * 不碰 parseFloat/Number(ESLint 硬拦)。maxPerMinute 是计数,非金额,直接展示。
 */

/** 规则类型枚举(api-gen RiskPolicyRequest.ruleType 描述)。 */
export type RuleType = 'MAX_NOTIONAL' | 'ORDER_FREQUENCY' | 'DAILY_LOSS_LIMIT' | 'MAX_INITIAL_MARGIN'

/** 规则描述(硬编码,展示在 RuleCard desc 段)。 */
export const RULE_DESCRIPTION: Record<RuleType, string> = {
  MAX_NOTIONAL: '单笔下单金额(名义价值)上限,超限拒单',
  ORDER_FREQUENCY: '每分钟下单次数上限,防刷单滥用',
  DAILY_LOSS_LIMIT: '单日累计亏损上限,触及自动停所有策略',
  MAX_INITIAL_MARGIN: 'PERP 初始保证金占用上限,超限拒单',
}

/** 规则中文短名(下拉选项 + RuleCard 标题用,替代枚举字面)。 */
export const RULE_LABEL: Record<RuleType, string> = {
  MAX_NOTIONAL: '单笔限额',
  ORDER_FREQUENCY: '下单频率',
  DAILY_LOSS_LIMIT: '日亏限额',
  MAX_INITIAL_MARGIN: '保证金占用上限',
}

/** 规则默认阈值(modal 新建时预填,用户可改;与 MaxInitialMarginEvaluator.DEFAULT 0.8 对齐)。 */
export const RULE_DEFAULT_VALUE: Record<RuleType, string> = {
  MAX_NOTIONAL: '5000',
  ORDER_FREQUENCY: '60',
  DAILY_LOSS_LIMIT: '500',
  MAX_INITIAL_MARGIN: '0.8',
}

/** 按 ruleType 取描述;未知 ruleType 兜底"自定义规则"。 */
export function ruleDesc(ruleType: string): string {
  return RULE_DESCRIPTION[ruleType as RuleType] ?? '自定义规则'
}

/** 规则首字母(展示在 RuleCard 左侧 32x32 方块)。MAX→M / ORDER→O / DAILY→D。 */
export function ruleInitial(ruleType: string): string {
  const seg = ruleType.split('_')[0]
  return seg[0] ?? '?'
}

/** params 键名(因 ruleType 而异)。前端 modal 构造 body + formatRuleValue 取值共用。 */
export const RULE_PARAM_KEY: Record<RuleType, string> = {
  MAX_NOTIONAL: 'maxNotionalUsdt',
  ORDER_FREQUENCY: 'maxPerMinute',
  DAILY_LOSS_LIMIT: 'maxLossUsdt',
  MAX_INITIAL_MARGIN: 'maxInitialMarginRatio',
}

/**
 * 格式化规则当前阈值(展示在 RuleCard "当前阈值"卡)。
 * - MAX_NOTIONAL: params.maxNotionalUsdt → `$ 5,000`(金额走 decimal.js)
 * - ORDER_FREQUENCY: params.maxPerMinute → `60/min`(计数,非金额)
 * - DAILY_LOSS_LIMIT: params.maxLossUsdt → `$ 500`(金额走 decimal.js)
 * - key 缺失 → `—`
 */
export function formatRuleValue(
  ruleType: string,
  params: { [key: string]: string } | undefined | null,
): string {
  const key = RULE_PARAM_KEY[ruleType as RuleType]
  if (!key) return '—'
  const raw = params?.[key]
  if (raw == null || raw === '') return '—'
  if (ruleType === 'ORDER_FREQUENCY') {
    // maxPerMinute 是计数,非金额,直接展示 + /min 后缀
    return `${raw}/min`
  }
  if (ruleType === 'MAX_INITIAL_MARGIN') {
    // 比例 (0,1] → 百分比,0.8 → 80%。走 decimal.js 不碰 Number
    return `${toDecimal(raw).times(100).toString()}%`
  }
  // 金额字段(maxNotionalUsdt/maxLossUsdt)走 decimal.js,不碰 parseFloat/Number
  return `$ ${formatMoney(toDecimal(raw))}`
}

/**
 * 把后端风控拒绝原因映射成用户可读文案。
 * - 命中已知规则枚举(MAX_NOTIONAL 等)→ 中文短名(RULE_LABEL)
 * - 未命中但 reason 含中文 → 原样透出(后端返中文描述)
 * - 纯英文兜底"触发风控规则"(防枚举/英文断言泄露给用户)
 */
export function mapRiskReason(reason: string | null | undefined): string {
  if (!reason) return '触发风控规则'
  for (const key of Object.keys(RULE_LABEL) as RuleType[]) {
    if (reason.includes(key)) return `触发「${RULE_LABEL[key]}」规则`
  }
  if (/[一-龥]/.test(reason)) return reason
  return '触发风控规则'
}
