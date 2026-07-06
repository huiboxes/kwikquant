import { describe, it, expect } from 'vitest'
import { backtestParametersSchema, backtestSubmitSchema } from './backtest'

describe('backtestParametersSchema', () => {
  it('正数 initial_capital 通过', () => {
    expect(backtestParametersSchema.safeParse({ initial_capital: 10000 }).success).toBe(true)
  })
  it('initial_capital = 0 失败', () => {
    expect(backtestParametersSchema.safeParse({ initial_capital: 0 }).success).toBe(false)
  })
  it('initial_capital 负数失败', () => {
    expect(backtestParametersSchema.safeParse({ initial_capital: -100 }).success).toBe(false)
  })
  it('initial_capital 缺失失败', () => {
    expect(backtestParametersSchema.safeParse({}).success).toBe(false)
  })
  it('键名 camelCase(initialCapital)被拒(snake_case 契约 G2)', () => {
    expect(backtestParametersSchema.safeParse({ initialCapital: 10000 }).success).toBe(false)
  })
})

describe('backtestSubmitSchema', () => {
  const valid = {
    strategyId: 1,
    symbol: 'BTC/USDT',
    exchange: 'BINANCE',
    intervalValue: '1h',
    startTime: '2026-06-01T00:00:00Z',
    endTime: '2026-07-01T00:00:00Z',
    parameters: { initial_capital: 10000 },
  }

  it('合法通过', () => {
    expect(backtestSubmitSchema.safeParse(valid).success).toBe(true)
  })

  it('startTime >= endTime 失败(error path=endTime)', () => {
    const r = backtestSubmitSchema.safeParse({
      ...valid,
      startTime: '2026-07-01T00:00:00Z',
      endTime: '2026-06-01T00:00:00Z',
    })
    expect(r.success).toBe(false)
    if (!r.success) {
      expect(r.error.issues.some((i) => i.path.includes('endTime'))).toBe(true)
    }
  })

  it('strategyId 非正失败', () => {
    expect(backtestSubmitSchema.safeParse({ ...valid, strategyId: 0 }).success).toBe(false)
  })

  it('symbol 空失败', () => {
    expect(backtestSubmitSchema.safeParse({ ...valid, symbol: '' }).success).toBe(false)
  })

  it('parameters.initial_capital 非正失败', () => {
    expect(
      backtestSubmitSchema.safeParse({ ...valid, parameters: { initial_capital: -1 } }).success,
    ).toBe(false)
  })
})
