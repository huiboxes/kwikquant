import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { WsConnectionIndicator } from './WsConnectionIndicator'
import { useWsStore } from '@/stores/wsStore'

describe('WsConnectionIndicator', () => {
  beforeEach(() => {
    useWsStore.getState().reset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('connected → "已连接" + 绿点(bg-up)', () => {
    useWsStore.getState().markConnected()
    render(<WsConnectionIndicator />)
    expect(screen.getByText('已连接')).toBeDefined()
    expect(screen.getByLabelText(/已连接/)).toBeDefined()
  })

  it('reconnecting → "重连中"', () => {
    useWsStore.setState({ status: 'reconnecting', attempt: 2 })
    render(<WsConnectionIndicator />)
    expect(screen.getByText('重连中')).toBeDefined()
  })

  it('failed → "已断开" + 断连 Banner', () => {
    useWsStore.setState({ status: 'failed', lastError: 'timeout' })
    const { container } = render(<WsConnectionIndicator />)
    expect(screen.getByText('已断开')).toBeDefined()
    expect(screen.getByRole('alert')).toBeDefined()
    expect(container.textContent).toContain('实时连接已断开')
  })

  it('connected 时不渲染断连 Banner', () => {
    useWsStore.getState().markConnected()
    const { container } = render(<WsConnectionIndicator />)
    expect(container.textContent).not.toContain('实时连接已断开')
  })

  it('auth_failed → "登录已失效" banner + 重新登录带当前页 from', () => {
    // stub 整页 location:用户停在 /trade?x=1 时会话失效,重登链接须带回跳目标
    const assignSpy = vi.fn()
    vi.stubGlobal('location', { pathname: '/trade', search: '?x=1', assign: assignSpy })
    useWsStore.setState({ status: 'auth_failed' })
    render(<WsConnectionIndicator />)
    expect(screen.getByText(/登录已失效，实时推送已停止/)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: '重新登录' }))
    expect(assignSpy).toHaveBeenCalledWith('/login?from=%2Ftrade%3Fx%3D1')
  })

  it('tooltip 含上次连接时间 + 重连次数', () => {
    useWsStore.setState({
      status: 'reconnecting',
      attempt: 3,
      lastConnectedAt: 1751700000000,
      lastError: 'connection reset',
    })
    const { container } = render(<WsConnectionIndicator />)
    const indicator = container.querySelector('[title]')
    expect(indicator?.getAttribute('title')).toContain('重连次数：3')
    expect(indicator?.getAttribute('title')).toContain('connection reset')
  })
})
