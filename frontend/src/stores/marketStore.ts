import { create } from 'zustand'
import { getWsConnection } from '@/lib/ws/ConnectionManager'
import { tickerDestination, type WsTicker } from '@/types/ws'

/**
 * marketStore — 行情 tick 缓存 + WS 订阅管理(阶段 4 补全,TD-011)。
 *
 * 数据源:`/topic/ticker/{exchange}/{marketType}/{sym-dash}` WS 推送 WsTicker
 * (ws-contract §3 / types/ws.ts,`MarketDataService.onTicker` 推完整 Ticker record)。
 * 模式照 notifStore:WS payload → set zustand,组件读 store(多 LivePrice 共享,避免 useWsTopic
 * 同 destination 单订阅限制 + 多组件重复订阅)。
 *
 * subscribeTickers 集中订阅 N 个 symbol(各 destination 不同,ConnectionManager Map key 不撞);
 * 重连重订阅由 ConnectionManager.onConnect 自管(subscriptions Map),订阅者无感。
 *
 * tickerTick 1.8s 心跳保留作 **WS 未连兜底**:LivePrice 无真实 tick 时降级 sin 抖动
 * (LivePrice 内部优先读 ticks[symbol].last,无才用 tickerTick 抖动)。
 *
 * module-level timer 测试泄漏风险:测试调 stopTicker + clearTicks 清理(同 wsStore resetWs 模式)。
 */
interface MarketState {
  /** 全局心跳计数(1.8s),WS 未连时驱动 LivePrice 兜底抖动。 */
  tickerTick: number
  /** per-symbol 最新 tick(canonical symbol key,如 "BTC/USDT")。 */
  ticks: Record<string, WsTicker>
  /** 启动 1.8s 心跳(幂等,已启动 no-op)。authed 后由 AppLayout/RequireAuth 调用。 */
  startTicker: () => void
  /** 停止心跳 + 清定时器(测试/登出清理)。 */
  stopTicker: () => void
  /** WS 推送更新 tick(MarketDataService.onTicker 推 → handler 调此)。 */
  updateTick: (symbol: string, tick: WsTicker) => void
  /**
   * 集中订阅 N 个 symbol 的 ticker WS。返回 unsubAll(退订全部)。
   * destination = /topic/ticker/{exchange}/{marketType}/{sym-dash}(见 types/ws.ts tickerDestination)。
   * 连接未就绪时 ConnectionManager 登记订阅,onConnect 后自动补订阅(broker 不持久化离线消息)。
   */
  subscribeTickers: (exchange: string, marketType: string, symbols: readonly string[]) => () => void
  /** 清 ticks(测试清理)。 */
  clearTicks: () => void
}

let timer: ReturnType<typeof setInterval> | null = null

export const useMarketStore = create<MarketState>()((set, get) => ({
  tickerTick: 0,
  ticks: {},
  startTicker: () => {
    if (timer) return
    timer = setInterval(() => set((s) => ({ tickerTick: s.tickerTick + 1 })), 1800)
  },
  stopTicker: () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  },
  updateTick: (symbol, tick) => set((s) => ({ ticks: { ...s.ticks, [symbol]: tick } })),
  subscribeTickers: (exchange, marketType, symbols) => {
    const unsubs: Array<() => void> = []
    const conn = getWsConnection()
    for (const symbol of symbols) {
      const dest = tickerDestination(exchange, marketType, symbol)
      const unsub = conn.subscribe(dest, (payload) => {
        // ConnectionManager 已 JSON.parse(msg.body),payload 是 Ticker record 对象。
        const tick = payload as WsTicker
        // key 用 tick.symbol(canonical 带斜杠,对齐 MARKET_SYMBOLS);缺失兜底用订阅 symbol。
        const key = tick?.symbol ?? symbol
        get().updateTick(key, tick)
      })
      unsubs.push(unsub)
    }
    return () => {
      for (const u of unsubs) u()
    }
  },
  clearTicks: () => set({ ticks: {} }),
}))
