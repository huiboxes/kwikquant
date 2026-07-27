import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { CommandMenu } from './CommandMenu'
import { useUiStore } from '@/stores/uiStore'

// CommandMenu 用 useAccounts(基准交易所)+ useMarketTickers(批量行情,成交额降序前 200 含 BTC/ETH
// 等主流标的,保主流不被 slice 截断),mock 返固定 data 避免 useQuery/QueryClientProvider/MSW 依赖。
// 标的分组用返的 BTC/USDT 断言。
vi.mock('@/hooks/useAccounts', () => ({
  useAccounts: () => ({ data: [{ id: 1, paperTrading: true, exchange: 'OKX' }] }),
}))
vi.mock('@/hooks/useMarketTickers', () => ({
  useMarketTickers: () => ({
    data: [
      { ticker: { symbol: 'BTC/USDT' }, stale: false },
      { ticker: { symbol: 'ETH/USDT' }, stale: false },
    ],
  }),
}))

describe('CommandMenu', () => {
  beforeEach(() => {
    useUiStore.setState({ cmdOpen: false, notifOpen: false, tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })

  it('cmdOpen=false 时不渲染输入框', () => {
    render(
      <MemoryRouter>
        <CommandMenu />
      </MemoryRouter>,
    )
    expect(screen.queryByPlaceholderText('搜索标的 / 页面 / 命令…')).not.toBeInTheDocument()
  })

  it('cmdOpen=true 时渲染输入框 + 导航命令 + 操作命令', () => {
    useUiStore.setState({ cmdOpen: true })
    render(
      <MemoryRouter>
        <CommandMenu />
      </MemoryRouter>,
    )
    expect(screen.getByPlaceholderText('搜索标的 / 页面 / 命令…')).toBeInTheDocument()
    expect(screen.getByText('BTC/USDT')).toBeInTheDocument()
    expect(screen.getByText('跳转：主页')).toBeInTheDocument()
    expect(screen.getByText('切换深 / 浅主题')).toBeInTheDocument()
    expect(screen.getByText('紧急停止 · 高风险')).toBeInTheDocument()
  })

  it('⌘K 打开命令面板', () => {
    render(
      <MemoryRouter>
        <CommandMenu />
      </MemoryRouter>,
    )
    expect(useUiStore.getState().cmdOpen).toBe(false)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true }))
    expect(useUiStore.getState().cmdOpen).toBe(true)
  })

  it('选命令项后关闭(cmdOpen=false)', async () => {
    useUiStore.setState({ cmdOpen: true })
    render(
      <MemoryRouter>
        <CommandMenu />
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByText('跳转：主页'))
    expect(useUiStore.getState().cmdOpen).toBe(false)
  })

  it('搜 BTC 命中 BTC/USDT(回归:主流标的不被 slice 截断 / fuzzy 不误匹配冷门标的)', async () => {
    useUiStore.setState({ cmdOpen: true })
    render(
      <MemoryRouter>
        <CommandMenu />
      </MemoryRouter>,
    )
    const input = screen.getByPlaceholderText('搜索标的 / 页面 / 命令…')
    await userEvent.type(input, 'BTC')
    expect(screen.getByText('BTC/USDT')).toBeInTheDocument()
  })
})
