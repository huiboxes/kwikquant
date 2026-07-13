import { toDecimal, formatMoney } from '@/lib/money'

/**
 * 风控规则展示辅助。
 *
 * 金额红线:params 里的 maxNotionalUsdt/maxLossUsdt 走 decimal.js(toDecimal + formatMoney),
 * 不碰 parseFloat/Number(ESLint 硬拦)。maxPerMinute 是计数,非金额,直接展示。
 */

/** 规则类型枚举(api-gen RiskPolicyRequest.ruleType 描述)。 */
export type RuleType = 'MAX_NOTIONAL' | 'ORDER_FREQUENCY' | 'DAILY_LOSS_LIMIT'

/** 规则描述(照 brief 硬编码,展示在 RuleCard desc 段)。 */
export const RULE_DESCRIPTION: Record<RuleType, string> = {
  MAX_NOTIONAL: '单笔下单金额(名义价值)上限,超限拒单',
  ORDER_FREQUENCY: '每分钟下单次数上限,防刷单滥用',
  DAILY_LOSS_LIMIT: '单日累计亏损上限,触及自动停所有策略',
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

/** params 键名(因 ruleType 而异)。 */
const RULE_PARAM_KEY: Record<RuleType, string> = {
  MAX_NOTIONAL: 'maxNotionalUsdt',
  ORDER_FREQUENCY: 'maxPerMinute',
  DAILY_LOSS_LIMIT: 'maxLossUsdt',
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
  // 金额字段(maxNotionalUsdt/maxLossUsdt)走 decimal.js,不碰 parseFloat/Number
  return `$ ${formatMoney(toDecimal(raw))}`
}
