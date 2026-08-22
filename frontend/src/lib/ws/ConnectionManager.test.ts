import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ConnectionManager } from './ConnectionManager'
import { useWsStore } from '@/stores/wsStore'
import { apiFetch, ApiError } from '@/lib/http'

interface MockClient {
  activate: ReturnType<typeof vi.fn>
  deactivate: ReturnType<typeof vi.fn>
  subscribe: ReturnType<typeof vi.fn>
  connected: boolean
  active: boolean
  _config: {
    brokerURL?: string
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

// ws-ticket 申请默认成功；单测内可覆盖 mock 行为(401 等)
vi.mock('@/lib/http', () => ({
  apiFetch: vi.fn(async () => ({ ticket: 't-123', expiresAt: '2026-01-01T00:00:30Z' })),
  ApiError: class extends Error {
    code: number
    status?: number
    constructor(code: number, message: string, status?: number) {
      super(message)
      this.code = code
      this.status = status
    }
    get isUnauthorized(): boolean {
      return this.code === 1001 || this.status === 401
    }
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

  it('connect() → status=connecting + ticket 申请后 activate(brokerURL 带 ticket)', async () => {
    const cm = new ConnectionManager('ws://localhost:8080/ws')
    cm.connect()
    expect(useWsStore.getState().status).toBe('connecting')
    expect(mockClient.activate).not.toHaveBeenCalled()
    await vi.runAllTimersAsync()
    expect(apiFetch).toHaveBeenCalledWith('/api/v1/auth/ws-ticket', { method: 'POST' })
    expect(mockClient.activate).toHaveBeenCalledOnce()
    expect(mockClient._config.brokerURL).toBe('ws://localhost:8080/ws?ticket=t-123')
  })

  it('ws-ticket 401 → auth_failed 且不 activate 不重连', async () => {
    vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(1001, 'unauthenticated', 401))
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    await vi.runAllTimersAsync()
    expect(useWsStore.getState().status).toBe('auth_failed')
    expect(mockClient.activate).not.toHaveBeenCalled()
  })

  it('ws-ticket 网络失败 → 走重连退避(非 auth_failed)，退避后重新申请 ticket 连上', async () => {
    vi.mocked(apiFetch).mockRejectedValueOnce(new Error('network down'))
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    // 首次申请网络失败 → 按连接失败退避(reconnecting)，不是 auth_failed
    await vi.advanceTimersByTimeAsync(0)
    expect(useWsStore.getState().status).toBe('reconnecting')
    expect(useWsStore.getState().attempt).toBe(1)
    expect(mockClient.activate).not.toHaveBeenCalled()
    // 退避 2s 后重新申请 ticket(一次性，重新签发)→ 成功建连
    await vi.advanceTimersByTimeAsync(2_000)
    expect(mockClient.activate).toHaveBeenCalledOnce()
    expect(mockClient._config.brokerURL).toBe('ws://x?ticket=t-123')
  })

  it('onConnect → markConnected(status=connected, attempt=0)', async () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    await vi.runAllTimersAsync()
    mockClient.connected = true
    mockClient._config.onConnect?.()
    expect(useWsStore.getState().status).toBe('connected')
    expect(useWsStore.getState().attempt).toBe(0)
  })

  it('onConnect 后已登记主题被重订阅', async () => {
    const cm = new ConnectionManager('ws://x')
    const handler = vi.fn()
    cm.subscribe('/topic/notifications/42', handler)
    expect(mockClient.subscribe).not.toHaveBeenCalled()
    cm.connect()
    await vi.runAllTimersAsync()
    mockClient.connected = true
    mockClient._config.onConnect?.()
    expect(mockClient.subscribe).toHaveBeenCalledWith(
      '/topic/notifications/42',
      expect.any(Function),
    )
  })

  it('onWebSocketClose → status=reconnecting + attempt+1 + 退避后重连(重新申请 ticket)', async () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    await vi.runAllTimersAsync()
    // 真实 stompjs 语义:activate 后 state=ACTIVE;ws close 后仍停留 ACTIVE(须 deactivate 才 INACTIVE)。
    // 守卫若查 active 会把死 client 误判为"已有连接",重连永久放弃
    mockClient.active = true
    mockClient._config.onConnect?.()
    mockClient.connected = true
    expect(mockClient.activate).toHaveBeenCalledOnce()
    // 第一次 close:connected 落 false 但 active 仍 true → attempt=1,nextDelay(1)=2000
    mockClient.connected = false
    mockClient._config.onWebSocketClose?.()
    expect(useWsStore.getState().status).toBe('reconnecting')
    expect(useWsStore.getState().attempt).toBe(1)
    // 退避 2s 内不重连
    await vi.advanceTimersByTimeAsync(1_900)
    expect(mockClient.activate).toHaveBeenCalledOnce()
    // 2s 后重连且重新申请 ticket(一次性，旧 ticket 已消费);旧死 client 先被 deactivate 清理
    await vi.advanceTimersByTimeAsync(200)
    expect(mockClient.activate).toHaveBeenCalledTimes(2)
    expect(apiFetch).toHaveBeenCalledTimes(2)
    expect(mockClient.deactivate).toHaveBeenCalled()
  })

  it('已建成连接(active+connected)时不重复建连(双 client 防线不受影响)', async () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    await vi.runAllTimersAsync()
    mockClient.active = true
    mockClient.connected = true
    mockClient._config.onConnect?.()
    // 再次 connect():client 活跃 → 幂等跳过,不申请新 ticket 不重建
    cm.connect()
    await vi.runAllTimersAsync()
    expect(mockClient.activate).toHaveBeenCalledOnce()
    expect(apiFetch).toHaveBeenCalledTimes(1)
  })

  it('subscribe 返 unsubscribe，调用安全', async () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    await vi.runAllTimersAsync()
    mockClient.connected = true
    mockClient._config.onConnect?.()
    const unsub = cm.subscribe('/topic/x', vi.fn())
    expect(() => unsub()).not.toThrow()
  })

  it('disconnect → deactivate + reset + close 不再重连(→failed)', async () => {
    const cm = new ConnectionManager('ws://x')
    cm.connect()
    await vi.runAllTimersAsync()
    mockClient._config.onConnect?.()
    cm.disconnect()
    expect(mockClient.deactivate).toHaveBeenCalled()
    expect(useWsStore.getState().status).toBe('idle')
    // close 后 shouldReconnect=false → failed，不再调度重连
    mockClient._config.onWebSocketClose?.()
    expect(useWsStore.getState().status).toBe('failed')
  })
})
