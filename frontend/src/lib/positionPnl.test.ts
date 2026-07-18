import { describe, it, expect } from 'vitest'
import Decimal from 'decimal.js'
import { sumUnrealizedPnl } from '@/lib/positionPnl'

/** 造一个只带 unrealizedPnl 的仓位(函数签名只要该字段,PositionDto 结构性兼容)。 */
function pos(uPnl: number | null) {
  return { unrealizedPnl: uPnl }
}

/** 非 null 用例 helper:先断言非 null,再断言等于期望值(number/string 都转 Decimal 避免浮点)。 */
function expectSum(
  input: Parameters<typeof sumUnrealizedPnl>[0],
  expected: number | string,
) {
  const got = sumUnrealizedPnl(input)
  expect(got).not.toBeNull()
  expect(got!.equals(new Decimal(expected))).toBe(true)
}

describe('sumUnrealizedPnl', () => {
  it('undefined → 0(无持仓)', () => expectSum(undefined, 0))
  it('null → 0', () => expectSum(null, 0))
  it('空数组 → 0', () => expectSum([], 0))
  it('单仓 → 该 uPnl', () => expectSum([pos(15.3)], '15.3'))
  it('多仓累加(正负混合)', () => expectSum([pos(10), pos(20.5), pos(-5)], '25.5'))
  it('小数精度(decimal.js 不丢精度)', () =>
    expectSum([pos(0.0025), pos(0.0001)], '0.0026'))

  it('任一 uPnl null → null(行情不可用,无法完整估值)', () => {
    expect(sumUnrealizedPnl([pos(10), pos(null)])).toBeNull()
  })

  it('全 null → null', () => {
    expect(sumUnrealizedPnl([pos(null), pos(null)])).toBeNull()
  })
})
