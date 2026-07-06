import { describe, it, expect } from 'vitest'
import {
  formatDuration,
  formatDrawdown,
  formatMoney,
  formatPercent,
  formatRatio,
  formatWinRate,
} from './backtestFormat'

describe('formatPercent', () => {
  it('0.1532 → "15.32%"', () => {
    expect(formatPercent(0.1532)).toBe('15.32%')
  })
  it('负值 -0.0842 → "-8.42%"', () => {
    expect(formatPercent(-0.0842)).toBe('-8.42%')
  })
  it('0 → "0.00%"', () => {
    expect(formatPercent(0)).toBe('0.00%')
  })
  it('1 → "100.00%"', () => {
    expect(formatPercent(1)).toBe('100.00%')
  })
  it('dp=4 自定义小数位', () => {
    expect(formatPercent(0.1532, 4)).toBe('15.3200%')
  })
})

describe('formatDrawdown', () => {
  it('-0.0842 → "-8.42%"', () => {
    expect(formatDrawdown(-0.0842)).toBe('-8.42%')
  })
})

describe('formatWinRate', () => {
  it('0.62 → "62.00%"', () => {
    expect(formatWinRate(0.62)).toBe('62.00%')
  })
})

describe('formatRatio', () => {
  it('1.85 → "1.85"', () => {
    expect(formatRatio(1.85)).toBe('1.85')
  })
  it('2.1 → "2.10"', () => {
    expect(formatRatio(2.1)).toBe('2.10')
  })
})

describe('formatDuration', () => {
  it('3600s → "1h 0m"', () => {
    expect(formatDuration(3600)).toBe('1h 0m')
  })
  it('9000s → "2h 30m"', () => {
    expect(formatDuration(9000)).toBe('2h 30m')
  })
  it('45s → "45s"', () => {
    expect(formatDuration(45)).toBe('45s')
  })
  it('0s → "0s"', () => {
    expect(formatDuration(0)).toBe('0s')
  })
  it('负数安全降级为 0s', () => {
    expect(formatDuration(-10)).toBe('0s')
  })
})

describe('formatMoney', () => {
  it('42150.5 → 千分位 + 2dp = "42,150.50"', () => {
    expect(formatMoney(42150.5)).toBe('42,150.50')
  })
  it('10000 → "10,000.00"', () => {
    expect(formatMoney(10000)).toBe('10,000.00')
  })
  it('null → "—"', () => {
    expect(formatMoney(null)).toBe('—')
  })
  it('空字符串 → "—"', () => {
    expect(formatMoney('')).toBe('—')
  })
})
