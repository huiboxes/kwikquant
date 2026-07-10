import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { CommandMenu } from './CommandMenu'
import { useUiStore } from '@/stores/uiStore'

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
    expect(screen.queryByPlaceholderText('搜索页面 / 跳转页面 / 命令…')).not.toBeInTheDocument()
  })

  it('cmdOpen=true 时渲染输入框 + 导航命令 + 操作命令', () => {
    useUiStore.setState({ cmdOpen: true })
    render(
      <MemoryRouter>
        <CommandMenu />
      </MemoryRouter>,
    )
    expect(screen.getByPlaceholderText('搜索页面 / 跳转页面 / 命令…')).toBeInTheDocument()
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
