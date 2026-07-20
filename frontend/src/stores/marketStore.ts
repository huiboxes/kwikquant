import { create } from 'zustand'
import { getWsConnection } from '@/lib/ws/ConnectionManager'
import { tickerDestination, type WsTicker } from '@/types/ws'

/**
 * marketStore — 行情 tick 缓存 + WS ticker 订阅管理。
 *
 * 数据源:`/topic/ticker/{exchange}/{marketType}/{sym-dash}` WS 推送 WsTicker
 * (ws-contract §3 / types/ws.ts,`MarketDataService.onTicker` 推整个 Ticker record)。
 * 模式照 notifStore:WS payload → set zustand,组件读 store(多 LivePrice 共享,避免 useWsTopic
 * 同 destination 单订阅限制 + 多组件重复订阅)。
 *
 * 两档订阅:
 *  - `subscribeTickers(批量)`:全局 persistent 8 symbol(MarketPage/TradingPage 启动订阅)。
 *  - `subscribeTicker(单个)`:非 persistent sel 按需订阅(用户 ⌘K 切到非 persistent → POST /subscribe
 *    起后端 worker + 此处订 destination 收 WS;切走 unsub + POST /unsubscribe)。idle 30s 退订兜底。
 *
 * `subscribedSymbols` Set 守卫:ConnectionManager destination-keyed 全局单订阅(marketStore.ts:11),
 * persistent 已订的 symbol 别重复 subscribe(会覆盖);subscribeTicker 先查 Set 已订则 no-op unsub。
 * 重连重订阅由 ConnectionManager.onConnect 自管(subscriptions Map),订阅者无感。
 *
 * tickerTick 1.8s 心跳保留作 **WS 未连兜底**:LivePrice 无真实 tick 时降级 sin 抖动
 * (LivePrice 内部优先读 ticks[symbol].last,无才用 tickerTick 抖动)。
 */
interface MarketState {
  /** 全局心跳计数(1.8s),WS 未连时驱动 LivePrice 兜底抖动。 */
  tickerTick: number
  /** per-symbol 最新 tick(canonical symbol key,如 "BTC/USDT")。 */
  ticks: Record<string, WsTicker>
  /** 已订阅 destination 的 canonical symbol Set(守卫避免 ConnectionManager 单订阅重复覆盖)。 */
  subscribedSymbols: Set<string>
  /** 启动 1.8s 心跳(幂等,已启动 no-op)。authed 后由 AppLayout/RequireAuth 调用。 */
  startTicker: () => void
  /** 停止心跳 + 清定时器(测试/登出清理)。 */
  stopTicker: () => void
  /** WS 推送更新 tick(MarketDataService.onTicker 推 → handler 调此)。 */
  updateTick: (symbol: string, tick: WsTicker) => void
  /**
   * 订阅单个 symbol 的 ticker WS(destination = /topic/ticker/{ex}/{mt}/{sym-dash})。
   * 已订阅(persistent 或之前按需)→ no-op unsub(避免重复覆盖 ConnectionManager 单订阅)。
   * 返 unsub(退订 destination + 移 Set);调用方在 sel 切走/卸载时调。
   */
  subscribeTicker: (exchange: string, marketType: string, symbol: string) => () => void
  /**
   * 集中订阅 N 个 symbol 的 ticker WS(复用 subscribeTicker,返回 unsubAll 退订全部)。
   */
  subscribeTickers: (exchange: string, marketType: string, symbols: readonly string[]) => () => void
  /** 清 ticks(测试清理)。 */
  clearTicks: () => void
}

let timer: ReturnType<typeof setInterval> | null = null

export const useMarketStore = create<MarketState>()((set, get) => ({
  tickerTick: 0,
  ticks: {},
  subscribedSymbols: new Set<string>(),
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
  subscribeTicker: (exchange, marketType, symbol) => {
    const key = symbol
    if (get().subscribedSymbols.has(key)) {
      // 已订(persistent 全局订了 或 sel 之前按需订过)→ no-op unsub,避免覆盖 ConnectionManager 单订阅
      return () => {}
    }
    const conn = getWsConnection()
    const dest = tickerDestination(exchange, marketType, symbol)
    const unsub = conn.subscribe(dest, (payload) => {
      // ConnectionManager 已 JSON.parse(msg.body),payload 是 Ticker record 对象。
      const tick = payload as WsTicker
      // key 用 tick.symbol(canonical 带斜杠,对齐 MARKET_SYMBOLS);缺失兜底用订阅 symbol。
      const key = tick?.symbol ?? symbol
      get().updateTick(key, tick)
    })
    set((s) => {
      const next = new Set(s.subscribedSymbols)
      next.add(key)
      return { subscribedSymbols: next }
    })
    return () => {
      unsub()
      set((s) => {
        const next = new Set(s.subscribedSymbols)
        next.delete(key)
        return { subscribedSymbols: next }
      })
    }
  },
  subscribeTickers: (exchange, marketType, symbols) => {
    const unsubs: Array<() => void> = []
    for (const symbol of symbols) {
      unsubs.push(get().subscribeTicker(exchange, marketType, symbol))
    }
    return () => {
      for (const u of unsubs) u()
    }
  },
  clearTicks: () => set({ ticks: {} }),
}))
