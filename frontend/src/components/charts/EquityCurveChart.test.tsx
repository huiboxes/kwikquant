import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { EquityCurveChart } from './EquityCurveChart'

/**
 * Y 轴刻度精度守卫(问题 3b 回归)。
 *
 * 原 bug:fmtShort 固定 `>=1000 ? (n/1000).toFixed(1)+'k'`。回测资金 100k 附近 ±50
 * 波动时，刻度 100050/100025/100000/99975/99950 全 rounding 成 '100.0k'/'100.1k',
 * Y 轴 5 个刻度有 4 个显 '100.0k' 看不出差异 —— 用户报告"纵轴都是 100.0K"。
 *
 * 修复:fmtShort 接收 max-min range，按 range 动态决定小数位(range 小→多显小数位)。
 * 回归:k 记号裸格式('94.0005k')改金额习惯千分位('94,001');≥1M 用 M 记号。
 * 本测试守卫:range=100(100k±50)时刻度必须可区分；刻度走千分位而非 k 记号。
 */
describe('EquityCurveChart Y 轴刻度精度', () => {
  /** 抽 Y 轴刻度 text(数字开头，排除 '暂无数据' 占位 + 多曲线图例)。 */
  const yAxisTexts = (container: HTMLElement): string[] =>
    Array.from(container.querySelectorAll('text'))
      .map((t) => t.textContent ?? '')
      .filter((s) => /^[0-9]/.test(s) && s !== '暂无数据')

  it('range=100(100k±50)时刻度可区分且为千分位格式', () => {
    const data: Array<[number, number]> = [
      [0, 99950],
      [1, 100000],
      [2, 100050],
    ]
    const { container } = render(<EquityCurveChart data={data} height={140} />)
    const ys = yAxisTexts(container)
    // 5 个刻度(间距 25)必须两两可区分，不再 rounding 成同一值
    expect(new Set(ys).size).toBeGreaterThan(1)
    // 千分位金额格式(非 k 记号):'100,050'/'99,950'
    expect(ys.some((s) => /^100,050$/.test(s))).toBe(true)
    expect(ys.some((s) => /^99,950$/.test(s))).toBe(true)
    expect(ys.some((s) => /k$/.test(s))).toBe(false)
  })

  it('range 大(50k→150k)时刻度千分位整数不碎成小数', () => {
    const data: Array<[number, number]> = [
      [0, 50000],
      [1, 100000],
      [2, 150000],
    ]
    const { container } = render(<EquityCurveChart data={data} height={140} />)
    const ys = yAxisTexts(container)
    // range=100k→decimals=0 → '50,000'/'100,000'/'150,000'(千分位整数)
    expect(ys.some((s) => /^150,000$/.test(s) || /^50,000$/.test(s))).toBe(true)
  })

  it('≥1M 权益用 M 记号防刻度溢出', () => {
    const data: Array<[number, number]> = [
      [0, 1_000_000],
      [1, 2_000_000],
      [2, 3_000_000],
    ]
    const { container } = render(<EquityCurveChart data={data} height={140} />)
    const ys = yAxisTexts(container)
    expect(ys.some((s) => /M$/.test(s))).toBe(true)
  })

  it('绝对值<1000 小波动(range=0.5)按 range 多显小数位', () => {
    const data: Array<[number, number]> = [
      [0, 0.2],
      [1, 0.5],
      [2, 0.7],
    ]
    const { container } = render(<EquityCurveChart data={data} height={140} />)
    const ys = yAxisTexts(container)
    // range=0.5<1→2 位小数，'0.20'/'0.50'/'0.70'(非 '0' 整数)
    expect(ys.some((s) => /\.\d{2}$/.test(s))).toBe(true)
  })
})

describe('EquityCurveChart showYAxis', () => {
  it('默认 showYAxis=true 显 Y 轴刻度文字', () => {
    const { container } = render(
      <EquityCurveChart data={[[0, 10000], [1, 11000], [2, 11560]]} width={300} height={140} />,
    )
    expect(container.querySelectorAll('text').length).toBeGreaterThan(0)
  })

  it('showYAxis=false 隐 Y 轴刻度文字(只留横虚线)', () => {
    const { container } = render(
      <EquityCurveChart data={[[0, 10000], [1, 11000], [2, 11560]]} width={300} height={140} showYAxis={false} />,
    )
    expect(container.querySelectorAll('line[stroke="var(--border-soft)"]').length).toBeGreaterThan(0)
    expect(container.querySelectorAll('text').length).toBe(0)
  })
})
