import Decimal from 'decimal.js'
import { toDecimal } from '@/lib/money'

/**
 * 持仓未实现盈亏聚合(sum unrealizedPnl)。
 *
 * PositionDto.unrealizedPnl 契约标 number 但运行时可为 null(行情不可用,
 * 见 api-gen PositionDto 注释"行情不可用时为 null")。本函数把 null 视为
 * "该仓位无法估值":
 *  - 任一仓位 unrealizedPnl 为 null → 返 null(无法完整估值,调用方显 —)
 *  - 全部有值 → 累加返 Decimal
 *  - 无持仓(undefined/null/[])→ toDecimal(0)
 *
 * 不 reduce realizedPnl(已实现是另一口径,见 PortfolioPnl.realizedPnl)。
 *
 * 用于 TradingPage BalanceBar 单账户 uPnl(TD-040 接入);接受任何带可选
 * unrealizedPnl 的对象数组,PositionDto[] 结构性兼容。
 */
export function sumUnrealizedPnl(
  positions: readonly { unrealizedPnl?: number | null }[] | undefined | null,
): Decimal | null {
  if (!positions || positions.length === 0) return toDecimal(0)
  let sum = toDecimal(0)
  for (const p of positions) {
    const v = p.unrealizedPnl
    if (v == null) return null // 任一不可估值 → 整体不可估值
    sum = sum.plus(toDecimal(v))
  }
  return sum
}
