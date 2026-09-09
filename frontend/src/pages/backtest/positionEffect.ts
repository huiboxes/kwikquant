/**
 * PERP 四向意图 → 用户可读中文(报告域展示/CSV 导出共用)。
 *
 * 与 TradingPage 下单 4 按钮同一文案纪律(DESIGN.md):用户可见文案是中文
 * (开多/开空/平多/平空),枚举字面量只作辅助(title/CSV 原值列)。
 * long 侧走 up 语义色、short 侧走 down 语义色(值复用,不引新色)。
 */
export const EFFECT_LABELS: Record<string, { text: string; long: boolean }> = {
  OPEN_LONG: { text: '开多', long: true },
  CLOSE_LONG: { text: '平多', long: true },
  OPEN_SHORT: { text: '开空', long: false },
  CLOSE_SHORT: { text: '平空', long: false },
}

/** effect → 中文文案;未知/空值回退原字面量(不吞数据,防御契约演进)。 */
export function effectLabel(effect: string | null | undefined): string {
  if (effect == null) return '—'
  return EFFECT_LABELS[effect]?.text ?? effect
}
