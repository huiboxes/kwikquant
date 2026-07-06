import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TradesTable } from './TradesTable'

const trades = [
  {
    id: 1024,
    time: '2026-06-15T08:30:00Z',
    side: 'buy',
    price: 42150.5,
    amount: 0.0025,
    fee: 0.0052,
  },
  {
    id: 1025,
    time: '2026-06-16T14:00:00Z',
    side: 'sell',
    price: 43120.8,
    amount: 0.0025,
    fee: 0.0053,
  },
]

describe('TradesTable', () => {
  it('渲染表头', () => {
    render(<TradesTable trades={trades} />)
    expect(screen.getByText('时间')).toBeInTheDocument()
    expect(screen.getByText('方向')).toBeInTheDocument()
    expect(screen.getByText('价格')).toBeInTheDocument()
    expect(screen.getByText('数量')).toBeInTheDocument()
    expect(screen.getByText('手续费')).toBeInTheDocument()
  })

  it('渲染交易行 + 方向 Chip(buy=买/sell=卖)', () => {
    render(<TradesTable trades={trades} />)
    expect(screen.getByText('买')).toBeInTheDocument()
    expect(screen.getByText('卖')).toBeInTheDocument()
  })

  it('时间 ISO → "YYYY-MM-DD HH:MM:SS UTC"(显式 UTC 标记)', () => {
    render(<TradesTable trades={trades} />)
    expect(screen.getByText(/2026-06-15 08:30:00 UTC/)).toBeInTheDocument()
    expect(screen.getByText(/2026-06-16 14:00:00 UTC/)).toBeInTheDocument()
  })

  it('价格金额格式化(千分位+2dp)', () => {
    render(<TradesTable trades={trades} />)
    expect(screen.getByText('42,150.50')).toBeInTheDocument()
    expect(screen.getByText('43,120.80')).toBeInTheDocument()
  })

  it('buy → up 色 Chip,sell → down 色 Chip', () => {
    render(<TradesTable trades={trades} />)
    expect(screen.getByText('买').className).toContain('text-up')
    expect(screen.getByText('卖').className).toContain('text-down')
  })

  it('空 trades 不崩(无行)', () => {
    render(<TradesTable trades={[]} />)
    expect(screen.getByText('时间')).toBeInTheDocument()
  })
})
