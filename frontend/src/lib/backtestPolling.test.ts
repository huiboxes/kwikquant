import { describe, it, expect } from 'vitest'
import { nextBacktestInterval, type BacktestStatus } from './backtestPolling'

describe('nextBacktestInterval', () => {
  describe('终态停止', () => {
    it('COMPLETED → false', () => {
      expect(nextBacktestInterval('COMPLETED', 0)).toBe(false)
    })
    it('FAILED → false', () => {
      expect(nextBacktestInterval('FAILED', 0)).toBe(false)
    })
    it('undefined(尚未拿到数据) → false', () => {
      expect(nextBacktestInterval(undefined, 0)).toBe(false)
    })
    it('COMPLETED 即使 attempt 大也停止', () => {
      expect(nextBacktestInterval('COMPLETED', 100)).toBe(false)
    })
  })

  describe('退避序列 2s/2s/4s/8s/10s(上限 10s)', () => {
    it('PENDING attempt=0 → 2000ms', () => {
      expect(nextBacktestInterval('PENDING', 0)).toBe(2_000)
    })
    it('RUNNING attempt=1 → 2000ms', () => {
      expect(nextBacktestInterval('RUNNING', 1)).toBe(2_000)
    })
    it('RUNNING attempt=2 → 4000ms', () => {
      expect(nextBacktestInterval('RUNNING', 2)).toBe(4_000)
    })
    it('RUNNING attempt=3 → 8000ms', () => {
      expect(nextBacktestInterval('RUNNING', 3)).toBe(8_000)
    })
    it('RUNNING attempt=4 → 10000ms(达上限)', () => {
      expect(nextBacktestInterval('RUNNING', 4)).toBe(10_000)
    })
    it('RUNNING attempt=5 → 10000ms(cap)', () => {
      expect(nextBacktestInterval('RUNNING', 5)).toBe(10_000)
    })
    it('RUNNING attempt=100 → 10000ms(cap)', () => {
      expect(nextBacktestInterval('RUNNING', 100)).toBe(10_000)
    })
  })

  describe('不超过 10s 上限', () => {
    it('所有非终态返值 ≤ 10000', () => {
      const statuses: BacktestStatus[] = ['PENDING', 'RUNNING']
      for (const s of statuses) {
        for (let a = 0; a < 20; a++) {
          const r = nextBacktestInterval(s, a)
          expect(r).not.toBe(false)
          expect(r as number).toBeLessThanOrEqual(10_000)
        }
      }
    })
  })
})
