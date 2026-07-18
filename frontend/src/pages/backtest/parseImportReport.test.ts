import { describe, it, expect } from 'vitest'
import { parseImportReport } from '@/pages/backtest/parseImportReport'

const VALID = JSON.stringify({
  name: 'BTC/USDT 网格回测',
  params: { gridNum: 10 },
  symbol: 'BTC/USDT',
  timeframe: '1h',
  period: { start: '2026-06-01T00:00:00Z', end: '2026-07-01T00:00:00Z' },
  trades: [{ time: '2026-06-15T08:30:00Z', side: 'buy', price: 42150.5, amount: 0.0025 }],
  equityCurve: [{ time: '2026-06-01T00:00:00Z', equity: 10000 }],
})

describe('parseImportReport', () => {
  it('合法 JSON 通过校验返回 data', () => {
    const r = parseImportReport(VALID)
    expect(r.ok).toBe(true)
    if (r.ok) expect(r.data.symbol).toBe('BTC/USDT')
  })

  it('非 JSON 文本返回格式错误', () => {
    const r = parseImportReport('{not json')
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('JSON')
  })

  it('根是数组返回错误', () => {
    expect(parseImportReport('[]').ok).toBe(false)
  })

  it('根是字符串返回错误', () => {
    expect(parseImportReport('"hello"').ok).toBe(false)
  })

  it('缺必填字段(symbol)返回错误并指出字段', () => {
    const obj = JSON.parse(VALID)
    delete obj.symbol
    const r = parseImportReport(JSON.stringify(obj))
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('symbol')
  })

  it('trades 空数组返回错误', () => {
    const obj = JSON.parse(VALID)
    obj.trades = []
    const r = parseImportReport(JSON.stringify(obj))
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('trades')
  })

  it('equityCurve 非数组返回错误', () => {
    const obj = JSON.parse(VALID)
    obj.equityCurve = 'not-array'
    const r = parseImportReport(JSON.stringify(obj))
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('equityCurve')
  })

  it('params 非对象返回错误', () => {
    const obj = JSON.parse(VALID)
    obj.params = 'x'
    const r = parseImportReport(JSON.stringify(obj))
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('params')
  })

  it('空字符串返回 JSON 格式错误', () => {
    const r = parseImportReport('')
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('JSON')
  })
})
