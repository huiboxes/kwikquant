import { describe, it, expect } from 'vitest'
import { mapBacktestError } from '@/pages/strategy/backtestError'

describe('mapBacktestError', () => {
  it('trades empty 关键词映射成 warning + 可行动建议(非 error)', () => {
    const r = mapBacktestError('trades must not be empty')
    expect(r.tone).toBe('warning')
    expect(r.title).toBe('回测完成,未产生成交')
    expect(r.description).toContain('区间')
  })

  it('no trades / empty trades 变体也命中 warning', () => {
    expect(mapBacktestError('no trades generated').tone).toBe('warning')
    expect(mapBacktestError('empty trades list').tone).toBe('warning')
  })

  it('真实异常文案原样透出,tone=error', () => {
    const r = mapBacktestError('strategy code syntax error: line 42')
    expect(r.tone).toBe('error')
    expect(r.title).toBe('回测失败')
    expect(r.description).toBe('strategy code syntax error: line 42')
  })

  it('error 为空走兜底:提示查 Worker 日志', () => {
    const r = mapBacktestError(null)
    expect(r.tone).toBe('error')
    expect(r.description).toContain('Worker')
  })

  it('undefined 同样走兜底', () => {
    expect(mapBacktestError(undefined).tone).toBe('error')
  })
})
