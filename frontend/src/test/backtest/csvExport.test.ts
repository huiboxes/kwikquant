import { describe, it, expect } from 'vitest'
import { buildBacktestCsv, sanitizeFileName } from '@/pages/backtest/csvExport'
import type { BacktestReportDetailDto } from '@/api/backtest'

const detail = {
  id: 1,
  name: 'backtest',
  symbol: 'BTC/USDT',
  timeframe: '1h',
  periodStart: '2026-01-01',
  periodEnd: '2026-06-01',
  metrics: {
    totalReturn: 0.15,
    sharpeRatio: 1.5,
    maxDrawdown: 0.05,
    winRate: 0.6,
    profitFactor: 1.8,
    totalTrades: 10,
    avgTradeDurationSeconds: 3600,
  },
  equityCurve: Array.from({ length: 2000 }, (_, i) => ({
    time: `2026-01-${String((i % 30) + 1).padStart(2, '0')}`,
    equity: String(10000 + i),
  })),
  trades: [
    { id: 1, reportId: 1, time: '2026-01-02T00:00:00Z', side: 'buy', price: 60000, amount: 0.5, fee: 30, realizedPnl: null, equity: 30000 },
    { id: 2, reportId: 1, time: '2026-01-03T00:00:00Z', side: 'sell', price: 62000, amount: 0.5, fee: 31, realizedPnl: 939, equity: 30939 },
  ],
} as unknown as BacktestReportDetailDto

describe('buildBacktestCsv', () => {
  it('contains BOM + 指标区 + 曲线采样段 + 明细', () => {
    const csv = buildBacktestCsv(detail, 'BTC 趋势')
    expect(csv.startsWith('﻿')).toBe(true)
    expect(csv).toContain('指标，值')
    expect(csv).toContain('总收益率，15.00%')
    expect(csv).toContain('策略，BTC 趋势')
    expect(csv).toContain('时间，权益')
    expect(csv).toContain('时间，方向，价格，数量，手续费，盈亏，权益')
    expect(csv).toContain('buy')
    expect(csv).toContain('sell')
  })

  it('samples equityCurve to <=1000 points (2000 → 1000)', () => {
    const csv = buildBacktestCsv(detail, 'BTC')
    const lines = csv.split('\n')
    const curveStart = lines.indexOf('时间，权益')
    const curveEnd = lines.indexOf('', curveStart)
    const curveLines = lines.slice(curveStart + 1, curveEnd)
    expect(curveLines.length).toBeLessThanOrEqual(1000)
    expect(curveLines.length).toBeGreaterThan(500)
  })

  it('escapes CSV injection for = + - @ prefix', () => {
    const evilDetail = {
      ...detail,
      trades: [
        { id: 1, reportId: 1, time: '2026-01-02T00:00:00Z', side: 'buy', price: '=CMD()', amount: '+1', fee: '-1', realizedPnl: '@evil', equity: 30000 },
      ],
    } as unknown as BacktestReportDetailDto
    const csv = buildBacktestCsv(evilDetail, 'BTC')
    expect(csv).toContain("'=CMD()")
    expect(csv).toContain("'+1")
    expect(csv).toContain("'-1")
    expect(csv).toContain("'@evil")
  })
})

describe('sanitizeFileName', () => {
  it('strips special chars', () => {
    expect(sanitizeFileName('BTC/USDT 趋势?')).toBe('BTC-USDT-趋势')
  })
})
