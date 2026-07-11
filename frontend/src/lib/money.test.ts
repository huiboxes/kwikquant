import Decimal from 'decimal.js'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { formatMoney, formatMoneyCompact, toDecimal } from './money'

describe('money', () => {
  describe('toDecimal', () => {
    let warnSpy: ReturnType<typeof vi.spyOn>

    beforeEach(() => {
      warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    })
    afterEach(() => {
      warnSpy.mockRestore()
    })

    it('null → Decimal(0) + console.warn', () => {
      expect(toDecimal(null).toString()).toBe('0')
      expect(warnSpy).toHaveBeenCalledOnce()
    })
    it('undefined → Decimal(0) + console.warn', () => {
      expect(toDecimal(undefined).toString()).toBe('0')
      expect(warnSpy).toHaveBeenCalledOnce()
    })
    it('empty string → Decimal(0) + console.warn', () => {
      expect(toDecimal('').toString()).toBe('0')
      expect(warnSpy).toHaveBeenCalledOnce()
    })
    it('valid string → Decimal 精度无损', () => {
      expect(toDecimal('1.234567890123456789').toString()).toBe('1.234567890123456789')
      expect(warnSpy).not.toHaveBeenCalled()
    })
    it('valid number → Decimal（兼容 OpenAPI 契约 number 标注）', () => {
      expect(toDecimal(1.23).toString()).toBe('1.23')
    })
    it('负数字符串 → Decimal', () => {
      expect(toDecimal('-100').toString()).toBe('-100')
    })
    it("非法 'NaN' 字符串 → 抛错（不静默归零）", () => {
      expect(() => toDecimal('NaN')).toThrow()
    })
    it("非法 'abc' 字符串 → 抛错", () => {
      expect(() => toDecimal('abc')).toThrow()
    })
  })

  describe('formatMoney', () => {
    it('默认 dp=2', () => {
      expect(formatMoney(new Decimal('1234.5'))).toBe('1,234.50')
    })
    it('千分位分隔（百万级）', () => {
      expect(formatMoney(new Decimal('1234567.89'))).toBe('1,234,567.89')
    })
    it('负数带 - 前缀 + 千分位', () => {
      expect(formatMoney(new Decimal('-1234567.89'))).toBe('-1,234,567.89')
    })
    it('dp=0 → 无小数位', () => {
      expect(formatMoney(new Decimal('1234.5'), { dp: 0 })).toBe('1,235')
    })
    it('dp=4 → 四位小数', () => {
      expect(formatMoney(new Decimal('1.23456'), { dp: 4 })).toBe('1.2346')
    })
    it('sign=true + 正数 → +前缀', () => {
      expect(formatMoney(new Decimal('1234'), { sign: true })).toBe('+1,234.00')
    })
    it('sign=true + 负数 → 仍是 - 前缀（不重复）', () => {
      expect(formatMoney(new Decimal('-1234'), { sign: true })).toBe('-1,234.00')
    })
    it('sign=true + 零 → 不加 +', () => {
      expect(formatMoney(new Decimal('0'), { sign: true })).toBe('0.00')
    })
    it('sign=false（默认）+ 正数 → 无前缀', () => {
      expect(formatMoney(new Decimal('1234'))).toBe('1,234.00')
    })
    it('三位以下无千分位', () => {
      expect(formatMoney(new Decimal('999.5'))).toBe('999.50')
    })
    it('零值', () => {
      expect(formatMoney(new Decimal('0'))).toBe('0.00')
    })
  })

  describe('formatMoneyCompact', () => {
    it('≥1e3 → X.Yk(124,556.99 → 124.6k)', () => {
      expect(formatMoneyCompact(toDecimal('124556.99'))).toBe('124.6k')
    })
    it('≥1e6 → X.YM(1,240,000 → 1.2M)', () => {
      expect(formatMoneyCompact(toDecimal('1240000'))).toBe('1.2M')
    })
    it('999 以下 → 整数(无 K/M)', () => {
      expect(formatMoneyCompact(toDecimal('999'))).toBe('999')
      expect(formatMoneyCompact(toDecimal('42'))).toBe('42')
    })
    it('1000 整 → 1.0k(边界)', () => {
      expect(formatMoneyCompact(toDecimal('1000'))).toBe('1.0k')
    })
    it('负数 → -前缀', () => {
      expect(formatMoneyCompact(toDecimal('-124556'))).toBe('-124.6k')
    })
    it('sign=true + 正数 → +前缀', () => {
      expect(formatMoneyCompact(toDecimal('124556'), { sign: true })).toBe('+124.6k')
    })
  })
})
