import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { AccountCard } from './AccountCard'
import type { components } from '@/types/api-gen'

type ExchangeAccountView = components['schemas']['ExchangeAccountView']

const paperAcc: ExchangeAccountView = {
  id: 1, exchange: 'BINANCE', label: '主账户',
  apiKey: '****ab12', paperTrading: true, status: 'ACTIVE',
} as ExchangeAccountView

const liveAcc: ExchangeAccountView = {
  id: 2, exchange: 'OKX', label: '实盘1',
  apiKey: '****cd34', paperTrading: false, status: 'ACTIVE',
} as ExchangeAccountView

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AccountCard', () => {
  it('readonly 模式(无回调):显 label/exchange,不显重置/删除按钮', () => {
    wrap(<AccountCard acc={paperAcc} />)
    expect(screen.getByText('主账户')).toBeInTheDocument()
    expect(screen.getByText('BINANCE')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /重置/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument()
  })

  it('managed 模拟盘:显重置按钮,点击触发 onReset', async () => {
    const onReset = vi.fn()
    const user = userEvent.setup()
    wrap(<AccountCard acc={paperAcc} onReset={onReset} onDelete={vi.fn()} />)
    const btn = screen.getByRole('button', { name: /重置/ })
    await user.click(btn)
    expect(onReset).toHaveBeenCalledOnce()
  })

  it('managed 实盘:不显重置按钮(LIVE 不可重置),显删除', () => {
    wrap(<AccountCard acc={liveAcc} onReset={vi.fn()} onDelete={vi.fn()} />)
    expect(screen.queryByRole('button', { name: /重置/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /删除/ })).toBeInTheDocument()
  })

  it('文案:徽章用中文模拟/实盘,不泄露 PAPER/LIVE 枚举', () => {
    const { rerender } = wrap(<AccountCard acc={paperAcc} />)
    expect(screen.getByText('模拟')).toBeInTheDocument()
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter><AccountCard acc={liveAcc} /></MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.getByText('● 实盘')).toBeInTheDocument()
  })

  it('文案:不泄露余额来源/基准行情实现细节', () => {
    wrap(<AccountCard acc={liveAcc} />)
    expect(screen.queryByText(/交易所维护余额/)).not.toBeInTheDocument()
    expect(screen.queryByText(/基准行情/)).not.toBeInTheDocument()
  })
})
