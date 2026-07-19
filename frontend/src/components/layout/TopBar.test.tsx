import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { TopBar } from './TopBar'
import { useUiStore } from '@/stores/uiStore'

function LocationCapture({ onCapture }: { onCapture: (p: string) => void }) {
  const loc = useLocation()
  onCapture(loc.pathname + loc.search)
  return <TopBar />
}

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
    await userEvent.click(screen.getByLabelText('打开命令面板'))
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

  it('点账户 chip 跳 /settings?tab=accounts', async () => {
    let pathname = ''
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route
            path="*"
            element={<LocationCapture onCapture={(p) => (pathname = p)} />}
          />
        </Routes>
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByLabelText('账户设置'))
    expect(pathname).toBe('/settings?tab=accounts')
  })
})
