import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { EquityCurveChart } from './EquityCurveChart'

/**
 * Y 轴刻度精度守卫(问题 3b 回归)。
 *
 * 原 bug:fmtShort 固定 `>=1000 ? (n/1000).toFixed(1)+'k'`。回测资金 100k 附近 ±50
 * 波动时,刻度 100050/100025/100000/99975/99950 全 rounding 成 '100.0k'/'100.1k',
 * Y 轴 5 个刻度有 4 个显 '100.0k' 看不出差异 —— 用户报告"纵轴都是 100.0K"。
 *
 * 修复:fmtShort 接收 max-min range,按 range 动态决定小数位(range 小→多显小数位)。
 * 本测试守卫:range=100(100k±50)时刻度必须可区分(至少 2 位小数);range 大时不退化。
 */
describe('EquityCurveChart Y 轴刻度精度', () => {
  /** 抽 Y 轴刻度 text(数字开头,排除 '暂无数据' 占位 + 多曲线图例)。 */
  const yAxisTexts = (container: HTMLElement): string[] =>
    Array.from(container.querySelectorAll('text'))
      .map((t) => t.textContent ?? '')
      .filter((s) => /^[0-9]/.test(s) && s !== '暂无数据')

  it('range=100(100k±50)时刻度可区分(不全 100.0k,至少 2 位小数)', () => {
    const data: Array<[number, number]> = [
      [0, 99950],
      [1, 100000],
      [2, 100050],
    ]
    const { container } = render(<EquityCurveChart data={data} height={140} />)
    const ys = yAxisTexts(container)
    // 原 bug:5 个刻度全显 '100.0k'(只 max/min 边缘 '100.1k');修复后应可区分。
    const allSame = ys.every((s) => s === '100.0k')
    expect(allSame).toBe(false)
    // 修复后 range=100→kRange=0.1→2 位小数,如 '100.05k'/'100.00k'/'99.95k'
    expect(ys.some((s) => /\.(\d{2})k$/.test(s))).toBe(true)
  })

  it('range 大(50k→150k,kRange=100)时刻度整数 k 不退化', () => {
    const data: Array<[number, number]> = [
      [0, 50000],
      [1, 100000],
      [2, 150000],
    ]
    const { container } = render(<EquityCurveChart data={data} height={140} />)
    const ys = yAxisTexts(container)
    // range=100k→kRange=100→decimals=0 → '50k'/'100k'/'150k'(整数 k,不碎成小数)
    expect(ys.some((s) => /^150k$/.test(s) || /^50k$/.test(s))).toBe(true)
  })

  it('绝对值<1000 小波动(range=0.5)按 range 多显小数位', () => {
    const data: Array<[number, number]> = [
      [0, 0.2],
      [1, 0.5],
      [2, 0.7],
    ]
    const { container } = render(<EquityCurveChart data={data} height={140} />)
    const ys = yAxisTexts(container)
    // range=0.5<1→2 位小数,'0.20'/'0.50'/'0.70'(非 '0' 整数)
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
