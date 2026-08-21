import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { NotifDrawer } from './NotifDrawer'
import { useUiStore } from '@/stores/uiStore'
import { useNotifStore } from '@/stores/notifStore'

// 测试用通知(WS 接真后 store 初始空，测试自行注入)
// ts 用真实 ISO 字符串(后端 NotificationEvent.timestamp 带精度 UTC，形如
// '2026-07-27T07:36:52.025260Z')。早期注入伪格式化 '2分钟前' 掩盖了 NotifDrawer
// 直接渲染 {n.ts} 漏 formatDateTime 的 bug —— 改真实 ISO + 加格式化守卫防回归。
const TEST_NOTIFS = [
  { id: 'n1', type: 'risk' as const, title: '风控拦截', body: 'o-9006 触发限额', ts: '2026-07-27T07:36:52.025260Z', unread: true },
  { id: 'n2', type: 'fill' as const, title: '订单成交', body: 'BTC/USDT BUY 已成交', ts: '2026-07-27T07:30:00.000Z', unread: true },
]

describe('NotifDrawer', () => {
  beforeEach(() => {
    useUiStore.setState({ cmdOpen: false, notifOpen: false, tradeMode: 'PAPER', liveConfirmedThisSession: false })
    useNotifStore.setState({ notifications: TEST_NOTIFS })
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

  it('点全部已读不崩，抽屉仍开', async () => {
    useUiStore.setState({ notifOpen: true })
    render(
      <MemoryRouter>
        <NotifDrawer />
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByText('全部已读'))
    expect(useUiStore.getState().notifOpen).toBe(true)
  })

  /**
   * 时间格式化守卫:NotifDrawer 必须把后端 ISO(带 T/Z 的 UTC，如
   * '2026-07-27T07:36:52.025260Z')走 formatDateTime 渲染成本地时区易读格式
   * (MM-dd HH:mm)，不能裸渲染 ISO。回归即测试红。
   * 不绑定时区具体值(UTC/UTC+8 输出不同)，只验格式不含 ISO 标志(T / Z)。
   */
  it('通知时间渲染为本地易读格式(不裸显 ISO 带 T/Z)', () => {
    useUiStore.setState({ notifOpen: true })
    render(
      <MemoryRouter>
        <NotifDrawer />
      </MemoryRouter>,
    )
    // 两条通知 ts 都应渲染成 MM-dd HH:mm 格式(本地时区)
    const tsEls = screen.getAllByText(/^[01]\d-[0-3]\d [0-2]\d:[0-5]\d$/)
    expect(tsEls).toHaveLength(2)
    // 不应出现裸 ISO 文本(以 'T...Z' 结尾)
    expect(screen.queryByText(/T[\d:.]+Z$/)).not.toBeInTheDocument()
  })
})
