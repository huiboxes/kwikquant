import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
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
  it('选中时走中性选中底色 + aria-current,不带品牌橙与指示条', () => {
    const { container } = render(
      <MemoryRouter>
        <BacktestCard bt={task} selected={true} onClick={() => {}} />
      </MemoryRouter>,
    )
    const card = container.querySelector('[data-selected="true"]')!
    expect(card.className).toContain('bg-interactive-selected')
    expect(card.className).not.toContain('border-accent')
    expect(card.className).not.toContain('bg-accent-soft')
    expect(card.getAttribute('aria-current')).toBe('true')
    // 选中信号靠底色 + aria,不挂竖条
    expect(container.querySelector('.bg-accent.shadow-glow')).toBeNull()
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

  it('失败任务显示失败引导而不是排队中', () => {
    render(
      <MemoryRouter>
        <BacktestCard bt={{ ...task, status: 'FAILED' }} selected={false} onClick={() => {}} />
      </MemoryRouter>,
    )
    expect(screen.getByText('回测失败 · 查看原因')).toBeInTheDocument()
    expect(screen.queryByText('排队中')).not.toBeInTheDocument()
  })

  it('组合任务显示汇总标签而非逗号拼接长串,完整清单进 title', () => {
    const symbols = ['BTC/USDT', 'ETH/USDT', 'SOL/USDT']
    render(
      <MemoryRouter>
        <BacktestCard
          bt={{
            ...task,
            symbol: symbols.join(','),
            symbols,
          }}
          selected={false}
          onClick={() => {}}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText(/组合·3 标的/)).toBeInTheDocument()
    expect(screen.queryByText(/BTC\/USDT,ETH\/USDT/)).not.toBeInTheDocument()
    expect(screen.getByTitle(symbols.join(', '))).toBeInTheDocument()
  })
})
