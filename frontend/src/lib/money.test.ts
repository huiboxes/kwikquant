import Decimal from 'decimal.js'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { formatMoney, formatMoneyCompact, sanitizeNumeric, toDecimal, tryToDecimal } from './money'

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

  describe('tryToDecimal', () => {
    // 渲染期派生场景的安全入口：非法输入不抛、降级 0，避免整页崩进错误边界。
    // 下单提交仍用严格 toDecimal，这里只兜底展示派生。
    it('合法字符串 → 正常 Decimal', () => {
      expect(tryToDecimal('1.5').toString()).toBe('1.5')
    })
    it('空值 → Decimal(0)', () => {
      expect(tryToDecimal('').toString()).toBe('0')
      expect(tryToDecimal(null).toString()).toBe('0')
    })
    it('非法中间态(如 "77230.5-")→ 不抛，降级 Decimal(0)', () => {
      expect(() => tryToDecimal('77230.5-')).not.toThrow()
      expect(tryToDecimal('77230.5-').toString()).toBe('0')
    })
    it('"abc" → 不抛，降级 Decimal(0)', () => {
      expect(() => tryToDecimal('abc')).not.toThrow()
      expect(tryToDecimal('abc').toString()).toBe('0')
    })
  })

  describe('sanitizeNumeric', () => {
    // 输入态过滤：只保留数字与单个小数点，过滤掉用户输入过程中的非法中间态(如 77230.5-)。
    it('纯数字原样保留', () => {
      expect(sanitizeNumeric('12345')).toBe('12345')
    })
    it('单个小数点保留', () => {
      expect(sanitizeNumeric('77230.5')).toBe('77230.5')
    })
    it('去掉非数字非小数点字符(如末尾 -)', () => {
      expect(sanitizeNumeric('77230.5-')).toBe('77230.5')
    })
    it('多个小数点只保留第一个', () => {
      expect(sanitizeNumeric('77.2.3')).toBe('77.2')
      expect(sanitizeNumeric('1.2.3.4')).toBe('1.2')
    })
    it('开头小数点保留(0.xx 输入态)', () => {
      expect(sanitizeNumeric('.5')).toBe('.5')
    })
    it('负号被过滤(价格/数量不允许负数)', () => {
      expect(sanitizeNumeric('-100')).toBe('100')
    })
    it('空字符串原样返回空', () => {
      expect(sanitizeNumeric('')).toBe('')
    })
    it('纯非法字符 → 空字符串', () => {
      expect(sanitizeNumeric('abc')).toBe('')
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
