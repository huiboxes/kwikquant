import { create } from 'zustand'
import { getWsConnection } from '@/lib/ws/ConnectionManager'
import { tickerDestination, type WsTicker } from '@/types/ws'

/**
 * marketStore — 行情 tick 缓存 + WS ticker 订阅管理(引用计数)。
 *
 * 数据源:`/topic/ticker/{exchange}/{marketType}/{sym-dash}` WS 推送 WsTicker
 * (见 docs/ws-contract.md / types/ws.ts,`MarketDataService.onTicker` 推整个 Ticker record)。
 * 模式照 notifStore:WS payload → set zustand，组件读 store(多 LivePrice 共享)。
 *
 * 订阅引用计数(`tickerSubs` 模块级 Map<destination, {count, unsub}>):
 *  - 多组件调 subscribeTicker(同 dest)→ 首次发 WS SUBSCRIBE(后端 onWsSubscribe 起 ticker worker),
 *    后续只 refCount++(共享单订阅 + 单 handler → 单次 updateTick，避免多组件 N 次 re-render)。
 *  - unsub:refCount--，最后一个(0)才发 WS UNSUBSCRIBE(后端 onWsUnsubscribe 退 worker)。
 *    修原 Set 守卫 + no-op unsub 缺陷：原第一个 unmount 就退订，后续组件收不到 tick + 后端 worker
 *    被误退(WS 驱动后 worker 退更严重)。
 *
 * 两档订阅:
 *  - `subscribeTickers`(批量):全局 persistent 3 symbol(TradingPage 启动订阅)。
 *  - `subscribeTicker`(单个):非 persistent sel 按需订阅(useSymbolSnapshot)。
 *
 * 重连重订阅由 ConnectionManager.onConnect 自管(subscriptions Map，重发 SUBSCRIBE);
 * 引用计数状态(tickerSubs)跨重连保留(dest 仍在，refCount 不变)。
 *
 * tickerTick 1.8s 心跳保留作 WS 未连兜底:LivePrice 无真实 tick 时降级 sin 抖动。
 */
interface MarketState {
  /** 全局心跳计数(1.8s),WS 未连时驱动 LivePrice 兜底抖动。 */
  tickerTick: number
  /** per-(exchange,marketType,symbol) 最新 tick。key = `${exchange}:${marketType}:${symbol}`,
   * 避免 SPOT/PERP 同 symbol 互相覆盖(切 PERP tab 时 SPOT persistent tick 不再覆盖 PERP tick)。 */
  ticks: Record<string, WsTicker>
  /** 启动 1.8s 心跳(幂等，已启动 no-op)。authed 后由 AppLayout/RequireAuth 调用。 */
  startTicker: () => void
  /** 停止心跳 + 清定时器(测试/登出清理)。 */
  stopTicker: () => void
  /** WS 推送更新 tick(MarketDataService.onTicker 推 → handler 调此)。key 三元组防 SPOT/PERP 覆盖。 */
  updateTick: (exchange: string, marketType: string, symbol: string, tick: WsTicker) => void
  /**
   * 订阅单个 symbol 的 ticker WS(destination = /topic/ticker/{ex}/{mt}/{sym-dash})。
   * 引用计数：多组件同 dest 共享单订阅，最后一个 unmount 才真退订。返 unsub(refCount--)。
   */
  subscribeTicker: (exchange: string, marketType: string, symbol: string) => () => void
  /** 集中订阅 N 个 symbol 的 ticker WS(复用 subscribeTicker，返回 unsubAll 退订全部)。 */
  subscribeTickers: (exchange: string, marketType: string, symbols: readonly string[]) => () => void
  /** 清 ticks + 退所有 ticker 订阅(测试清理，setup.ts afterEach 调)。 */
  clearTicks: () => void
}

let timer: ReturnType<typeof setInterval> | null = null

/** destination → {refCount, unsub} 引用计数(多组件同 dest 共享单订阅，最后一个才退)。 */
interface TickerSub {
  count: number
  unsub: () => void
}
const tickerSubs = new Map<string, TickerSub>()

/** refCount--,0 才真 unsub + 从 Map 移除(最后一个订阅者退订)。 */
function releaseTickerSub(dest: string, sub: TickerSub): void {
  sub.count--
  if (sub.count <= 0) {
    sub.unsub()
    tickerSubs.delete(dest)
  }
}

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
  updateTick: (exchange, marketType, symbol, tick) =>
    set((s) => ({ ticks: { ...s.ticks, [`${exchange}:${marketType}:${symbol}`]: tick } })),
  subscribeTicker: (exchange, marketType, symbol) => {
    const dest = tickerDestination(exchange, marketType, symbol)
    const existing = tickerSubs.get(dest)
    if (existing) {
      // 已订：只 refCount++，共享单订阅(单 handler 单次 updateTick，避免 N 次 re-render)
      existing.count++
      return () => releaseTickerSub(dest, existing)
    }
    const conn = getWsConnection()
    const unsub = conn.subscribe(dest, (payload) => {
      // ConnectionManager 已 JSON.parse(msg.body),payload 是 Ticker record 对象。
      const tick = payload as WsTicker
      // key 三元组(exchange:marketType:symbol)防 SPOT/PERP 同 symbol 互相覆盖；
      // 用订阅的 exchange/marketType/symbol(闭包)，不取 tick 字段(WS payload 可能缺)。
      get().updateTick(exchange, marketType, symbol, tick)
    })
    const sub: TickerSub = { count: 1, unsub }
    tickerSubs.set(dest, sub)
    return () => releaseTickerSub(dest, sub)
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
  clearTicks: () => {
    // 退所有 ticker 订阅(refCount 归零)+ 清 Map + 清 ticks 缓存(测试清理)
    for (const sub of tickerSubs.values()) sub.unsub()
    tickerSubs.clear()
    set({ ticks: {} })
  },
}))
