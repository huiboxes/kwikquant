import { Client, type StompSubscription, type IMessage } from '@stomp/stompjs'
import { nextDelay } from './nextDelay'
import { useWsStore } from '@/stores/wsStore'

/**
 * ConnectionManager — STOMP over WebSocket 封装(spec §5 step 9)。
 *
 * 配置(契约 F ws-contract §8):
 *   - heartbeatIncoming/Outgoing: 10000(10s 心跳)
 *   - reconnectDelay: 0(禁用库内自动重连,固定延时非指数退避)
 *   - beforeConnect 手动 setTimeout 实现指数退避:1s→2s→5s→10s→30s(上限)
 *   - onWebSocketClose 触发重连
 *
 * 鉴权(ws-contract §1):HTTP 握手带 refresh_token cookie(path=/),浏览器自动附带,
 *   不走 STOMP CONNECT Bearer header。前端无需额外处理 cookie。
 *
 * 重连后重新 SUBSCRIBE 全部主题(broker 不持久化离线消息,维护订阅集合)。
 *
 * 单测:直接 mock @stomp/stompjs Client 层(vi.mock),验证 connect/重连/重订阅链路。
 * smoke 集成测放批 1a E2E 真实后端(step 17)。
 */
export type WsMessageHandler = (payload: unknown) => void

interface SubscriptionEntry {
  destination: string
  handler: WsMessageHandler
  stompSub?: StompSubscription
}

export class ConnectionManager {
  private client: Client | null = null
  private subscriptions: Map<string, SubscriptionEntry> = new Map()
  private connectTimer: ReturnType<typeof setTimeout> | undefined
  private attempt = 0
  private shouldReconnect = false
  private readonly url: string

  constructor(url: string) {
    this.url = url
  }

  /** 发起连接(幂等,重复调安全) */
  connect(): void {
    this.shouldReconnect = true
    if (this.client?.active) return
    this.scheduleConnect(0)
  }

  private scheduleConnect(delayMs: number): void {
    if (this.connectTimer) clearTimeout(this.connectTimer)
    const wsStore = useWsStore.getState()
    wsStore.setStatus(this.attempt === 0 ? 'connecting' : 'reconnecting')
    this.connectTimer = setTimeout(() => this.doConnect(), delayMs)
  }

  private doConnect(): void {
    this.client = new Client({
      brokerURL: this.url,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      reconnectDelay: 0,
      onConnect: () => this.onConnect(),
      onWebSocketClose: () => this.onWebSocketClose(),
      onStompError: (frame) => {
        const msg = frame.headers['message'] ?? 'STOMP error'
        useWsStore.getState().markError(msg)
      },
    })
    this.client.activate()
  }

  private onConnect(): void {
    this.attempt = 0
    useWsStore.getState().markConnected()
    // 重连后重新订阅全部主题(broker 不持久化离线消息)
    for (const entry of this.subscriptions.values()) {
      this.subscribeInternal(entry)
    }
  }

  private onWebSocketClose(): void {
    if (!this.shouldReconnect) {
      useWsStore.getState().setStatus('failed')
      return
    }
    this.attempt += 1
    useWsStore.getState().incAttempt()
    this.scheduleConnect(nextDelay(this.attempt))
  }

  private subscribeInternal(entry: SubscriptionEntry): void {
    if (!this.client?.connected) return
    entry.stompSub = this.client.subscribe(entry.destination, (msg: IMessage) => {
      let payload: unknown
      try {
        payload = JSON.parse(msg.body)
      } catch {
        payload = msg.body
      }
      entry.handler(payload)
    })
  }

  /**
   * 订阅主题,返 unsubscribe 函数。
   * 连接未就绪时仅登记,连接后 onConnect 会补订阅。
   */
  subscribe(destination: string, handler: WsMessageHandler): () => void {
    const entry: SubscriptionEntry = { destination, handler }
    this.subscriptions.set(destination, entry)
    this.subscribeInternal(entry)
    return () => {
      entry.stompSub?.unsubscribe()
      this.subscriptions.delete(destination)
    }
  }

  /** 主动断开(页面卸载/登出),不再重连 */
  disconnect(): void {
    this.shouldReconnect = false
    if (this.connectTimer) clearTimeout(this.connectTimer)
    for (const entry of this.subscriptions.values()) {
      entry.stompSub?.unsubscribe()
      entry.stompSub = undefined
    }
    this.client?.deactivate()
    this.client = null
    useWsStore.getState().reset()
  }
}

/**
 * 全局单例(WS 是一条连接,app 生命周期内复用)。
 * url 来自 VITE_WS_URL 或默认同源 /ws-endpoint(vite proxy 转后端)。
 */
let instance: ConnectionManager | null = null

export function getWsConnection(url: string): ConnectionManager {
  if (!instance) instance = new ConnectionManager(url)
  return instance
}

/** 测试用:重置单例(setup.ts afterEach 调用防泄漏) */
export function resetWsConnection(): void {
  instance = null
}
