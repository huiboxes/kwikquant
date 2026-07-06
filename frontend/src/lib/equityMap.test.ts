import { describe, it, expect } from 'vitest'
import { mapEquityCurve } from './equityMap'

describe('mapEquityCurve', () => {
  it('EquityPointDto[] → {time:UTCTimestamp, value:number}[] 升序', () => {
    const input = [
      { time: '2026-06-15T08:30:00Z', equity: 10532.18 },
      { time: '2026-06-01T00:00:00Z', equity: 10000 },
    ]
    const out = mapEquityCurve(input)
    expect(out).toHaveLength(2)
    // 升序:第一个 time < 第二个 time
    expect((out[0].time as number) < (out[1].time as number)).toBe(true)
    // 第一个是 06-01(equity=10000)
    expect(out[0].value).toBe(10000)
    expect(out[0].time).toBe(Math.floor(Date.parse('2026-06-01T00:00:00Z') / 1000))
    // 第二个是 06-15(equity=10532.18)
    expect(out[1].value).toBeCloseTo(10532.18, 2)
  })

  it('time 转为 UTCTimestamp 秒数(非 ISO 字符串)', () => {
    const out = mapEquityCurve([{ time: '2026-06-15T08:30:00Z', equity: 1 }])
    expect(typeof out[0].time).toBe('number')
    expect(out[0].time).toBe(Math.floor(Date.parse('2026-06-15T08:30:00Z') / 1000))
  })

  it('equity number → value number(Decimal 转换无损)', () => {
    const out = mapEquityCurve([{ time: '2026-06-01T00:00:00Z', equity: 10532.18 }])
    expect(out[0].value).toBeCloseTo(10532.18, 2)
  })

  it('空数组 → 空数组', () => {
    expect(mapEquityCurve([])).toEqual([])
  })

  it('已是升序则保持', () => {
    const input = [
      { time: '2026-06-01T00:00:00Z', equity: 10000 },
      { time: '2026-06-02T00:00:00Z', equity: 10100 },
    ]
    const out = mapEquityCurve(input)
    expect((out[0].time as number) < (out[1].time as number)).toBe(true)
  })
})
