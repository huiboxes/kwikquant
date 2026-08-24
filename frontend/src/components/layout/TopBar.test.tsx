import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { TopBar } from './TopBar'
import { useUiStore } from '@/stores/uiStore'

describe('TopBar', () => {
  beforeEach(() => {
    useUiStore.setState({ cmdOpen: false, notifOpen: false, tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })

  it('渲染面包屑 KwikQuant + 当前页名(/)', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <TopBar />
      </MemoryRouter>,
    )
    expect(screen.getByText('KwikQuant')).toBeInTheDocument()
    expect(screen.getByText('主页')).toBeInTheDocument()
  })

  it('当前路径 /strategy 时面包屑页名 = 策略工作台', () => {
    render(
      <MemoryRouter initialEntries={['/strategy']}>
        <TopBar />
      </MemoryRouter>,
    )
    expect(screen.getByText('策略工作台')).toBeInTheDocument()
  })

  it('点搜索触发器开命令面板(cmdOpen=true)', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <TopBar />
      </MemoryRouter>,
    )
    // 桌面搜索框 + 移动端 icon 两个触发器同 accessible name(真实视口互斥，jsdom 同渲染)，取其一
    await userEvent.click(screen.getAllByLabelText('打开命令面板')[0])
    expect(useUiStore.getState().cmdOpen).toBe(true)
  })

  it('点通知按钮开抽屉(notifOpen=true)', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <TopBar />
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByLabelText('通知'))
    expect(useUiStore.getState().notifOpen).toBe(true)
  })

  it('账户 chip 渲染模式标签(PAPER=模拟盘)', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <TopBar />
      </MemoryRouter>,
    )
    expect(screen.getByText('模拟盘')).toBeInTheDocument()
  })

  it('账户 chip 渲染模式标签(LIVE=实盘)', () => {
    useUiStore.setState({ tradeMode: 'LIVE' })
    render(
      <MemoryRouter initialEntries={['/']}>
        <TopBar />
      </MemoryRouter>,
    )
    // "实盘" 同时出现在 TradeModeToggle 和账户 chip 中，用 getAllByText 确认至少 2 处
    expect(screen.getAllByText('实盘').length).toBeGreaterThanOrEqual(2)
  })

})

describe('TopBar 账户菜单', () => {
  it('头像点开菜单:设置 / 账户与密码 / 退出登录', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<TopBar />} />
        </Routes>
      </MemoryRouter>,
    )
    await user.click(screen.getByRole('button', { name: /账户/ }))
    expect(await screen.findByText('账户与密码')).toBeInTheDocument()
    expect(screen.getByText('退出登录')).toBeInTheDocument()
  })
})
