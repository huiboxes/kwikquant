import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { StartDialog } from '@/pages/strategy/StartDialog'
import type { StrategyDetailDto } from '@/api/strategy'
import type { components } from '@/types/api-gen'

type ExchangeAccountView = components['schemas']['ExchangeAccountView']

const baseStrategy = (
  status: StrategyDetailDto['status'],
  stopReason = '',
): StrategyDetailDto => ({
  id: 1,
  name: 'BTC Trend Rider',
  description: '',
  symbol: 'BTC/USDT',
  exchange: 'BINANCE',
  marketType: 'SPOT',
  marginMode: null,
  leverage: null,
  intervalValue: '15m',
  status,
  parameters: '{}',
  createdAt: '2026-07-01T08:00:00Z',
  updatedAt: '2026-07-09T12:00:00Z',
  version: 'v1.3.2',
  pnl: 0,
  exchangeAccountId: 1,
  stopReason,
})

// accounts 只填 StartDialog 渲染用到的字段(id/label/paperTrading/testnet),cast 避开 strict 缺字段
const accounts = [
  { id: 1, label: 'Binance 主', paperTrading: true, testnet: false },
] as ExchangeAccountView[]

describe('StartDialog', () => {
  it('STOPPED 状态标题为「重新启动策略」+ 描述含「已发布的代码版本」', () => {
    render(
      <MemoryRouter>
      <StartDialog
        open
        onOpenChange={() => {}}
        strategy={baseStrategy('STOPPED')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
      />
      </MemoryRouter>,
    )
    expect(screen.getByText('重新启动策略')).toBeInTheDocument()
    expect(screen.getByText(/已发布的代码版本/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重新启动/ })).toBeInTheDocument()
  })

  it('READY 状态标题为「启动策略」(不变)', () => {
    render(
      <MemoryRouter>
      <StartDialog
        open
        onOpenChange={() => {}}
        strategy={baseStrategy('READY')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
      />
      </MemoryRouter>,
    )
    expect(screen.getByText('启动策略')).toBeInTheDocument()
  })

  it('STOPPED 点「先去编辑代码」调 onEditCode 并关闭 dialog', () => {
    const onEditCode = vi.fn()
    const onOpenChange = vi.fn()
    render(
      <MemoryRouter>
      <StartDialog
        open
        onOpenChange={onOpenChange}
        strategy={baseStrategy('STOPPED')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
        onEditCode={onEditCode}
      />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('button', { name: /先去编辑代码/ }))
    expect(onEditCode).toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('STOPPED + stopReason 非空 → 显示「上次因 X 停止」提示条', () => {
    render(
      <MemoryRouter>
      <StartDialog
        open
        onOpenChange={() => {}}
        strategy={baseStrategy('STOPPED', 'worker 健康检查失败')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
      />
      </MemoryRouter>,
    )
    expect(screen.getByText(/上次因/)).toBeInTheDocument()
    expect(screen.getByText(/worker 健康检查失败/)).toBeInTheDocument()
  })

  it('STOPPED + hasUnpublishedDraft → 显示「有未发布草稿」提示条', () => {
    render(
      <MemoryRouter>
      <StartDialog
        open
        onOpenChange={() => {}}
        strategy={baseStrategy('STOPPED')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
        hasUnpublishedDraft
      />
      </MemoryRouter>,
    )
    expect(screen.getByText(/有未发布的代码改动/)).toBeInTheDocument()
  })

  it('READY + stopReason 空 → 不显示停止提示条', () => {
    render(
      <MemoryRouter>
      <StartDialog
        open
        onOpenChange={() => {}}
        strategy={baseStrategy('READY')}
        accounts={accounts}
        starting={false}
        onStart={() => {}}
        hasUnpublishedDraft
      />
      </MemoryRouter>,
    )
    expect(screen.queryByText(/上次因/)).not.toBeInTheDocument()
    expect(screen.queryByText(/有未发布的代码改动/)).not.toBeInTheDocument()
  })
})
