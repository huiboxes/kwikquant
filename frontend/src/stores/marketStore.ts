import { create } from 'zustand'

/**
 * marketStore(最小版 — 阶段 2 提前建 tick 部分,阶段 4 补全)。
 *
 * 当前仅全局 tickerTick(1.8s 心跳,对齐原型 AppContext.jsx 的 setInterval),
 * 驱动 LivePrice/Ticker 价格闪烁。所有价格共用一个 tick → 同步跳动(原型设计意图)。
 *
 * 阶段 4 补全:接 WS tick 推送(tickerTick 由真实成交/报价驱动,而非定时器)+
 * ticker/kline 缓存 + 订阅管理。届时 startTicker 的定时器降级为 WS 未连时的兜底。
 *
 * module-level timer 测试泄漏风险:测试调 stopTicker 清理(见 wsStore resetWs 同模式)。
 */
interface MarketState {
  /** 全局心跳计数,每次 +1(1.8s)。LivePrice/Ticker 读它做闪烁/抖动。 */
  tickerTick: number
  /** 启动 1.8s 心跳(幂等,已启动则 no-op)。authed 后由 AppLayout/RequireAuth 调用。 */
  startTicker: () => void
  /** 停止心跳 + 清定时器(测试/登出清理)。 */
  stopTicker: () => void
}

let timer: ReturnType<typeof setInterval> | null = null

export const useMarketStore = create<MarketState>()((set) => ({
  tickerTick: 0,
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
}))
