import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BacktestCard } from './BacktestRail'
import type { BacktestTaskDto } from '@/api/backtest'

const task = {
  id: 1,
  strategyId: 10,
  strategyCodeId: 100,
  status: 'COMPLETED',
  symbol: 'BTC/USDT',
  exchange: 'OKX',
  intervalValue: '1h',
  startTime: '2026-01-01',
  endTime: '2026-06-01',
  parameters: '{}',
  result: null,
  reportId: 2201,
  errorMessage: null,
  processedBars: null,
  totalBars: null,
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
  totalReturn: 0.156,
  strategyName: 'rsi-reversal',
} as unknown as BacktestTaskDto

describe('BacktestCard 选中态', () => {
  it('选中时含 border-accent + bg-accent-soft + 左侧竖条 + aria-current', () => {
    const { container } = render(
      <MemoryRouter>
        <BacktestCard bt={task} selected={true} onClick={() => {}} />
      </MemoryRouter>,
    )
    const card = container.querySelector('[data-selected="true"]')!
    expect(card.className).toContain('border-accent')
    expect(card.className).toContain('bg-accent-soft')
    expect(card.getAttribute('aria-current')).toBe('true')
    // 左侧 accent 竖条(非颜色冗余信号)
    const bar = container.querySelector('.bg-accent.shadow-glow')
    expect(bar).not.toBeNull()
  })

  it('未选中时无 accent 类 + 无 aria-current + hover 态 border-border-soft', () => {
    const { container } = render(
      <MemoryRouter>
        <BacktestCard bt={task} selected={false} onClick={() => {}} />
      </MemoryRouter>,
    )
    const card = container.querySelector('[data-selected="false"]')!
    expect(card.className).not.toContain('border-accent')
    expect(card.className).toContain('border-border-soft')
    expect(card.getAttribute('aria-current')).toBeNull()
  })
})
