import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { WsConnectionIndicator } from './WsConnectionIndicator'
import { useWsStore } from '@/stores/wsStore'

describe('WsConnectionIndicator', () => {
  beforeEach(() => {
    useWsStore.getState().reset()
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

  it('tooltip 含上次连接时间 + 重连次数', () => {
    useWsStore.setState({
      status: 'reconnecting',
      attempt: 3,
      lastConnectedAt: 1751700000000,
      lastError: 'connection reset',
    })
    const { container } = render(<WsConnectionIndicator />)
    const indicator = container.querySelector('[title]')
    expect(indicator?.getAttribute('title')).toContain('重连次数: 3')
    expect(indicator?.getAttribute('title')).toContain('connection reset')
  })
})
