import { describe, expect, it } from 'vitest'
import { freshnessLabel, isStale } from './freshness'

// 固定 now 便于测试（不依赖真实时钟）
const NOW = new Date('2026-07-05T12:00:00Z')

function past(seconds: number): Date {
  return new Date(NOW.getTime() - seconds * 1000)
}
function future(seconds: number): Date {
  return new Date(NOW.getTime() + seconds * 1000)
}

describe('freshness', () => {
  describe('isStale (阈值默认 30s)', () => {
    it('1 秒前 → 新鲜', () => {
      expect(isStale(past(1), 30, NOW)).toBe(false)
    })
    it('29 秒前 → 新鲜（阈值内）', () => {
      expect(isStale(past(29), 30, NOW)).toBe(false)
    })
    it('30 秒整前 → 新鲜（严格 >）', () => {
      expect(isStale(past(30), 30, NOW)).toBe(false)
    })
    it('31 秒前 → 过期', () => {
      expect(isStale(past(31), 30, NOW)).toBe(true)
    })
    it('未来时刻（时钟偏移）→ 新鲜', () => {
      expect(isStale(future(10), 30, NOW)).toBe(false)
    })
    it('自定义阈值 60s 生效', () => {
      expect(isStale(past(45), 60, NOW)).toBe(false)
      expect(isStale(past(61), 60, NOW)).toBe(true)
    })
    it('默认阈值 = 30s（不传 thresholdSec）', () => {
      expect(isStale(past(29), undefined, NOW)).toBe(false)
      expect(isStale(past(31), undefined, NOW)).toBe(true)
    })
  })

  describe('freshnessLabel', () => {
    it('0 秒前 → 刚刚', () => {
      expect(freshnessLabel(past(0), NOW)).toBe('刚刚')
    })
    it('4 秒前 → 刚刚（<5s 归为刚刚）', () => {
      expect(freshnessLabel(past(4), NOW)).toBe('刚刚')
    })
    it('5 秒前 → 5 秒前', () => {
      expect(freshnessLabel(past(5), NOW)).toBe('5 秒前')
    })
    it('59 秒前 → 59 秒前（分钟边界前）', () => {
      expect(freshnessLabel(past(59), NOW)).toBe('59 秒前')
    })
    it('60 秒前 → 1 分钟前', () => {
      expect(freshnessLabel(past(60), NOW)).toBe('1 分钟前')
    })
    it('59 分 59 秒前 → 59 分钟前', () => {
      expect(freshnessLabel(past(59 * 60 + 59), NOW)).toBe('59 分钟前')
    })
    it('60 分钟前 → 1 小时前', () => {
      expect(freshnessLabel(past(3600), NOW)).toBe('1 小时前')
    })
    it('23 小时 59 分钟前 → 23 小时前', () => {
      expect(freshnessLabel(past(23 * 3600 + 59 * 60), NOW)).toBe('23 小时前')
    })
    it('24 小时前 → 1 天前', () => {
      expect(freshnessLabel(past(24 * 3600), NOW)).toBe('1 天前')
    })
    it('7 天前 → 7 天前', () => {
      expect(freshnessLabel(past(7 * 24 * 3600), NOW)).toBe('7 天前')
    })
    it('未来时刻 → 刚刚（负 age 视为 0）', () => {
      expect(freshnessLabel(future(10), NOW)).toBe('刚刚')
    })
  })
})
