import { describe, expect, it } from 'vitest'
import { equityColor, hasMeaningfulCurve } from './equity'

describe('hasMeaningfulCurve', () => {
  it('空数组 → false', () => {
    expect(hasMeaningfulCurve([])).toBe(false)
  })

  it('单点 → false', () => {
    expect(hasMeaningfulCurve([[0, 100000]])).toBe(false)
  })

  it('全零平线(后端无成交占位) → false', () => {
    expect(
      hasMeaningfulCurve([
        [0, 0],
        [1, 0],
        [2, 0],
      ]),
    ).toBe(false)
  })

  it('非零平线(资金未动) → false(无波动的平线观感像坏图，降级占位)', () => {
    expect(
      hasMeaningfulCurve([
        [0, 100000],
        [1, 100000],
      ]),
    ).toBe(false)
  })

  it('有波动 → true', () => {
    expect(
      hasMeaningfulCurve([
        [0, 100000],
        [1, 99500],
        [2, 100400],
      ]),
    ).toBe(true)
  })
})

describe('equityColor', () => {
  it('末值高于首值 → 涨色', () => {
    expect(
      equityColor([
        [0, 100000],
        [1, 105000],
      ]),
    ).toBe('var(--up)')
  })

  it('末值低于首值(亏损曲线) → 跌色', () => {
    expect(
      equityColor([
        [0, 100000],
        [1, 94730],
      ]),
    ).toBe('var(--down)')
  })

  it('首末持平 → 涨色(不渲染亏损语义)', () => {
    expect(
      equityColor([
        [0, 100000],
        [1, 100000],
      ]),
    ).toBe('var(--up)')
  })

  it('小数差额不被浮点误差吞掉', () => {
    // 0.1+0.2 类误差场景:权益差额 0.000001 也要判对方向
    expect(
      equityColor([
        [0, 1],
        [1, 1.000001],
      ]),
    ).toBe('var(--up)')
    expect(
      equityColor([
        [0, 1],
        [1, 0.999999],
      ]),
    ).toBe('var(--down)')
  })
})
