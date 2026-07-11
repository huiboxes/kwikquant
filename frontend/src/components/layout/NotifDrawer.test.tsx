import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { NotifDrawer } from './NotifDrawer'
import { useUiStore } from '@/stores/uiStore'

describe('NotifDrawer', () => {
  beforeEach(() => {
    useUiStore.setState({ cmdOpen: false, notifOpen: false, tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })

  it('notifOpen=false 时不渲染', () => {
    render(
      <MemoryRouter>
        <NotifDrawer />
      </MemoryRouter>,
    )
    expect(screen.queryByText('通知')).not.toBeInTheDocument()
  })

  it('notifOpen=true 渲染标题 + 4 tabs + mock 通知项', () => {
    useUiStore.setState({ notifOpen: true })
    render(
      <MemoryRouter>
        <NotifDrawer />
      </MemoryRouter>,
    )
    expect(screen.getByText('通知')).toBeInTheDocument()
    for (const t of ['全部', '未读', '风控', '策略']) {
      expect(screen.getByRole('tab', { name: t })).toBeInTheDocument()
    }
    expect(screen.getByText('风控拦截')).toBeInTheDocument()
    expect(screen.getByText('订单成交')).toBeInTheDocument()
  })

  it('tab=风控 只显风控类(订单成交消失)', async () => {
    useUiStore.setState({ notifOpen: true })
    render(
      <MemoryRouter>
        <NotifDrawer />
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByRole('tab', { name: '风控' }))
    expect(screen.getByText('风控拦截')).toBeInTheDocument()
    expect(screen.queryByText('订单成交')).not.toBeInTheDocument()
  })

  it('点偏好关闭抽屉(notifOpen=false)', async () => {
    useUiStore.setState({ notifOpen: true })
    render(
      <MemoryRouter initialEntries={['/']}>
        <NotifDrawer />
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByText('偏好'))
    expect(useUiStore.getState().notifOpen).toBe(false)
  })

  it('点全部已读不崩,抽屉仍开', async () => {
    useUiStore.setState({ notifOpen: true })
    render(
      <MemoryRouter>
        <NotifDrawer />
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByText('全部已读'))
    expect(useUiStore.getState().notifOpen).toBe(true)
  })
})
