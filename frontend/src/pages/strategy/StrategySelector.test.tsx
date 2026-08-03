import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { StrategySelector } from './StrategySelector'
import type { StrategyDetailDto } from '@/api/strategy'

const STRATS: StrategyDetailDto[] = [
  {
    id: 1,
    name: 'BTC Trend Rider',
    description: '',
    symbol: 'BTC/USDT',
    exchange: 'OKX',
    marketType: 'SPOT',
    intervalValue: '15m',
    status: 'RUNNING',
    parameters: '{}',
    createdAt: '',
    updatedAt: '',
    version: 'v1',
    pnl: 0,
    exchangeAccountId: 1,
  } as unknown as StrategyDetailDto,
  {
    id: 2,
    name: 'ETH Grid',
    description: '',
    symbol: 'ETH/USDT',
    exchange: 'OKX',
    marketType: 'SPOT',
    intervalValue: '1h',
    status: 'DRAFT',
    parameters: '{}',
    createdAt: '',
    updatedAt: '',
    version: 'v1',
    pnl: 0,
    exchangeAccountId: 1,
  } as unknown as StrategyDetailDto,
]

function renderSelector(props: Partial<Parameters<typeof StrategySelector>[0]> = {}) {
  const onSelect = vi.fn()
  return {
    onSelect,
    ...render(
      <MemoryRouter>
        <StrategySelector
          strategies={STRATS}
          selectedId={1}
          onSelect={onSelect}
          selected={STRATS[0]!}
          draftCodeId={null}
          onCreate={vi.fn()}
          onPublish={vi.fn()}
          onStart={vi.fn()}
          onPause={vi.fn()}
          onStop={vi.fn()}
          onDelete={vi.fn()}
          onFsm={vi.fn()}
          {...props}
        />
      </MemoryRouter>,
    ),
  }
}

// 注:Popover 关时 PopoverContent 不渲染,CommandItem 不在 DOM;trigger 显 selected name。
// 过滤断言:输 X 后 assert 命中项在 CommandItem + 不显 Empty(trigger 仍显 selected,不参与过滤判断)。

describe('StrategySelector', () => {
  it('trigger 显示选中策略 name', () => {
    renderSelector()
    // Popover 关,trigger 唯一显 selected name(:94 span 是 symbol/exchange 不含 name)
    expect(screen.getByText(/BTC Trend Rider/)).toBeInTheDocument()
  })

  it('输入搜索过滤:输 ETH 命中 ETH Grid', async () => {
    const user = userEvent.setup()
    renderSelector()
    await user.click(screen.getByRole('button', { name: /BTC Trend Rider/ }))
    const input = await screen.findByPlaceholderText('搜索策略…')
    await user.type(input, 'ETH')
    // CommandItem 列表:ETH Grid 命中(trigger/:94 不含 ETH Grid,唯一)
    expect(screen.getByText(/ETH Grid/)).toBeInTheDocument()
    expect(screen.queryByText('无匹配策略')).not.toBeInTheDocument()
  })

  it('输 name 子串也命中(不区分大小写):trend 命中 BTC Trend Rider', async () => {
    const user = userEvent.setup()
    renderSelector()
    await user.click(screen.getByRole('button', { name: /BTC Trend Rider/ }))
    const input = await screen.findByPlaceholderText('搜索策略…')
    await user.type(input, 'trend')
    // trend 命中 BTC Trend Rider(name),不显 Empty;ETH Grid 被过滤
    expect(screen.queryByText('无匹配策略')).not.toBeInTheDocument()
    expect(screen.queryByText(/ETH Grid/)).not.toBeInTheDocument()
  })

  it('无匹配显 CommandEmpty', async () => {
    const user = userEvent.setup()
    renderSelector()
    await user.click(screen.getByRole('button', { name: /BTC Trend Rider/ }))
    const input = await screen.findByPlaceholderText('搜索策略…')
    await user.type(input, 'zzz')
    expect(screen.getByText('无匹配策略')).toBeInTheDocument()
  })

  it('点选项调 onSelect + 关闭', async () => {
    const user = userEvent.setup()
    const { onSelect } = renderSelector()
    await user.click(screen.getByRole('button', { name: /BTC Trend Rider/ }))
    const input = await screen.findByPlaceholderText('搜索策略…')
    await user.type(input, 'ETH')
    await user.click(screen.getByText('ETH Grid'))
    expect(onSelect).toHaveBeenCalledWith(2)
  })
})
