import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { CommandMenu } from './CommandMenu'
import { useUiStore } from '@/stores/uiStore'

// CommandMenu 用 useAccounts(基准交易所)+ usePairs(全量标的),mock 返固定 data 避免
// useQuery/QueryClientProvider/MSW 依赖。标的分组用返的 BTC/USDT 断言。
vi.mock('@/hooks/useAccounts', () => ({
  useAccounts: () => ({ data: [{ id: 1, paperTrading: true, exchange: 'OKX' }] }),
}))
vi.mock('@/hooks/useMarket', () => ({
  usePairs: () => ({
    data: [
      { symbol: 'BTC/USDT', active: true, baseAsset: 'BTC', quoteAsset: 'USDT' },
      { symbol: 'ETH/USDT', active: true, baseAsset: 'ETH', quoteAsset: 'USDT' },
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
})
