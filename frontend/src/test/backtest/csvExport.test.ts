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
    expect(csv).toContain('指标,值')
    expect(csv).toContain('总收益率,15.00%')
    expect(csv).toContain('策略,BTC 趋势')
    expect(csv).toContain('时间,权益')
    expect(csv).toContain('时间,方向,价格,数量,手续费,盈亏,权益')
    expect(csv).toContain('buy')
    expect(csv).toContain('sell')
  })

  it('samples equityCurve to <=1000 points (2000 → 1000)', () => {
    const csv = buildBacktestCsv(detail, 'BTC')
    const lines = csv.split('\n')
    const curveStart = lines.indexOf('时间,权益')
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

// ---- PERP 导出(perp-backtest-spec §8.1:SPOT 表头逐字节不变,PERP 加声明与扩展列) ----

const perpDetail = {
  id: 2,
  name: 'perp',
  symbol: 'BTC/USDT:USDT',
  marketType: 'PERP',
  liquidationModel: 'BAR_EXTREME_APPROX',
  timeframe: '1h',
  periodStart: '2026-01-01',
  periodEnd: '2026-06-01',
  metrics: detail.metrics,
  equityCurve: [
    { time: '2026-01-01T00:00:00Z', equity: '10000', marginUsed: '420', fundingCum: '0' },
    { time: '2026-01-02T00:00:00Z', equity: '9500', marginUsed: '0', fundingCum: '-12.5' },
  ],
  trades: [
    { id: 1, time: '2026-01-01T08:00:00Z', side: 'buy', positionEffect: 'OPEN_LONG', liquidation: false, price: 42000, amount: 1, fee: 8.4, realizedPnl: -8.4, equity: null },
    { id: 2, time: '2026-01-01T12:00:00Z', side: 'sell', positionEffect: 'CLOSE_LONG', liquidation: true, price: 38000, amount: 1, fee: 7.6, realizedPnl: -4007.6, equity: null },
  ],
} as unknown as BacktestReportDetailDto

describe('buildBacktestCsv PERP', () => {
  it('指标区带市场类型与强平模型声明', () => {
    const csv = buildBacktestCsv(perpDetail, 'PERP 趋势')
    expect(csv).toContain('市场类型,PERP')
    expect(csv).toContain('强平模型,bar 极值近似(BAR_EXTREME_APPROX)')
  })

  it('曲线与明细表头加扩展列,行含持仓意图中文与强平标记', () => {
    const csv = buildBacktestCsv(perpDetail, 'PERP 趋势')
    expect(csv).toContain('时间,权益,保证金占用,累计资金费')
    expect(csv).toContain('时间,方向,持仓意图,价格,数量(币),手续费,盈亏,强平')
    expect(csv).toContain('开多')
    expect(csv).toContain('平多')
    // 强平行行尾标记"是",普通行空
    const lines = csv.split('\n')
    expect(lines.some((l) => l.endsWith(',是'))).toBe(true)
    // 曲线扩展列值(marginUsed/fundingCum;负值走 CSV 注入防御加 ' 前缀)
    expect(csv).toContain('10000,420,0')
    expect(csv).toContain("9500,0,'-12.5")
  })

  it('SPOT 报告表头不变(无 PERP 扩展列)', () => {
    const csv = buildBacktestCsv(detail, 'BTC')
    expect(csv).not.toContain('保证金占用')
    expect(csv).not.toContain('持仓意图')
    expect(csv).not.toContain('市场类型')
  })
})
