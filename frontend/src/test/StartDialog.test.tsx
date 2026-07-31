import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { StartDialog } from '@/pages/strategy/StartDialog'
import type { StrategyDetailDto } from '@/api/strategy'
import type { components } from '@/types/api-gen'

type ExchangeAccountView = components['schemas']['ExchangeAccountView']

const baseStrategy = (status: StrategyDetailDto['status']): StrategyDetailDto => ({
  id: 1,
  name: 'BTC Trend Rider',
  description: '',
  symbol: 'BTC/USDT',
  exchange: 'BINANCE',
  marketType: 'SPOT',
  intervalValue: '15m',
  status,
  parameters: '{}',
  createdAt: '2026-07-01T08:00:00Z',
  updatedAt: '2026-07-09T12:00:00Z',
  version: 'v1.3.2',
  pnl: 0,
  exchangeAccountId: 1,
})

// accounts 只填 StartDialog 渲染用到的字段(id/label/paperTrading/testnet),cast 避开 strict 缺字段
const accounts = [
  { id: 1, label: 'Binance 主', paperTrading: true, testnet: false },
] as ExchangeAccountView[]

describe('StartDialog', () => {
  it('STOPPED 状态标题为「重新启动策略」+ 描述含「已发布的代码版本」', () => {
    render(
      <StartDialog
        open
        onOpenChange={() => {}}
        strategy={baseStrategy('STOPPED')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
      />,
    )
    expect(screen.getByText('重新启动策略')).toBeInTheDocument()
    expect(screen.getByText(/已发布的代码版本/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重新启动/ })).toBeInTheDocument()
  })

  it('READY 状态标题为「启动策略」(不变)', () => {
    render(
      <StartDialog
        open
        onOpenChange={() => {}}
        strategy={baseStrategy('READY')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
      />,
    )
    expect(screen.getByText('启动策略')).toBeInTheDocument()
  })

  it('STOPPED 点「先去编辑代码」调 onEditCode 并关闭 dialog', () => {
    const onEditCode = vi.fn()
    const onOpenChange = vi.fn()
    render(
      <StartDialog
        open
        onOpenChange={onOpenChange}
        strategy={baseStrategy('STOPPED')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
        onEditCode={onEditCode}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /先去编辑代码/ }))
    expect(onEditCode).toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
