import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { BacktestMetricsPanel } from './BacktestMetricsPanel'

const metrics = {
  totalReturn: 0.1532,
  sharpeRatio: 1.85,
  maxDrawdown: -0.0842,
  winRate: 0.62,
  profitFactor: 2.1,
  totalTrades: 128,
  avgTradeDurationSeconds: 3600,
}

describe('BacktestMetricsPanel', () => {
  it('渲染所有指标格式化值', () => {
    render(<BacktestMetricsPanel metrics={metrics} />)
    expect(screen.getByText('15.32%')).toBeInTheDocument()
    expect(screen.getAllByText('1.85').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('-8.42%')).toBeInTheDocument()
    expect(screen.getByText('62.00%')).toBeInTheDocument()
    expect(screen.getByText('2.10')).toBeInTheDocument()
    expect(screen.getByText('128')).toBeInTheDocument()
    expect(screen.getByText('1h 0m')).toBeInTheDocument()
  })

  it('totalReturn 正 → up 色(涨绿)', () => {
    render(<BacktestMetricsPanel metrics={metrics} />)
    expect(screen.getByText('15.32%').className).toContain('text-up')
  })

  it('totalReturn 负 → down 色(跌红)', () => {
    render(<BacktestMetricsPanel metrics={{ ...metrics, totalReturn: -0.05 }} />)
    expect(screen.getByText('-5.00%').className).toContain('text-down')
  })

  it('maxDrawdown 始终 down 色', () => {
    render(<BacktestMetricsPanel metrics={metrics} />)
    expect(screen.getByText('-8.42%').className).toContain('text-down')
  })
})
