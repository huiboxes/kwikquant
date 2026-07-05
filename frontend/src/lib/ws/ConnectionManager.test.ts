import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ConnectionManager } from './ConnectionManager'
import { useWsStore } from '@/stores/wsStore'

interface MockClient {
  activate: ReturnType<typeof vi.fn>
  deactivate: ReturnType<typeof vi.fn>
  subscribe: ReturnType<typeof vi.fn>
  connected: boolean
  active: boolean
  _config: {
    onConnect?: () => void
    onWebSocketClose?: () => void
    onStompError?: (frame: { headers: Record<string, string> }) => void
  }
}

const { mockClient } = vi.hoisted(() => {
  const c: MockClient = {
    activate: vi.fn(),
    deactivate: vi.fn(),
    subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })),
    connected: false,
    active: false,
    _config: {},
  }
  return { mockClient: c }
})

vi.mock('@stomp/stompjs', () => ({
  // function constructor:new Client(config) 返 mockClient(函数返对象时 new 用该对象)
  Client: function (config: MockClient['_config']) {
    mockClient._config = config
    return mockClient
  },
}))

describe('ConnectionManager', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    mockClient.connected = false
    mockClient.active = false
    mockClient._config = {}
    useWsStore.getState().reset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('connect() → status=connecting + setTimeout(0) 后 activate', () => {
    const cm = new ConnectionManager('ws://localhost:8080/ws')
    cm.connect()
    expect(useWsStore.getState().status).toBe('connecting')
    expect(mockClient.activate).not.toHaveBeenCalled()
    vi.runAllTimers()
    expect(mockClient.activate).toHaveBeenCalledOnce()
  })

  it('onConnect → markConnected(status=connected, attempt=0)', () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    vi.runAllTimers()
    mockClient.connected = true
    mockClient._config.onConnect?.()
    expect(useWsStore.getState().status).toBe('connected')
    expect(useWsStore.getState().attempt).toBe(0)
  })

  it('onConnect 后已登记主题被重订阅', () => {
    const cm = new ConnectionManager('ws://x')
    const handler = vi.fn()
    cm.subscribe('/topic/notifications/42', handler)
    expect(mockClient.subscribe).not.toHaveBeenCalled()
    cm.connect()
    vi.runAllTimers()
    mockClient.connected = true
    mockClient._config.onConnect?.()
    expect(mockClient.subscribe).toHaveBeenCalledWith(
      '/topic/notifications/42',
      expect.any(Function),
    )
  })

  it('onWebSocketClose → status=reconnecting + attempt+1 + 退避后重连', () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    vi.runAllTimers()
    mockClient._config.onConnect?.()
    expect(mockClient.activate).toHaveBeenCalledOnce()
    // 第一次 close → attempt=1,nextDelay(1)=2000
    mockClient._config.onWebSocketClose?.()
    expect(useWsStore.getState().status).toBe('reconnecting')
    expect(useWsStore.getState().attempt).toBe(1)
    // 退避 2s 内不重连
    vi.advanceTimersByTime(1_900)
    expect(mockClient.activate).toHaveBeenCalledOnce()
    // 2s 后重连
    vi.advanceTimersByTime(200)
    expect(mockClient.activate).toHaveBeenCalledTimes(2)
  })

  it('subscribe 返 unsubscribe,调用安全', () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    vi.runAllTimers()
    mockClient.connected = true
    mockClient._config.onConnect?.()
    const unsub = cm.subscribe('/topic/x', vi.fn())
    expect(() => unsub()).not.toThrow()
  })

  it('disconnect → deactivate + reset + close 不再重连(→failed)', () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    vi.runAllTimers()
    mockClient._config.onConnect?.()
    cm.disconnect()
    expect(mockClient.deactivate).toHaveBeenCalled()
    expect(useWsStore.getState().status).toBe('idle')
    // close 后 shouldReconnect=false → failed,不再调度重连
    mockClient._config.onWebSocketClose?.()
    expect(useWsStore.getState().status).toBe('failed')
  })
})
