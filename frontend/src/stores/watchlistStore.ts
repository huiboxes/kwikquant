import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * watchlistStore — 用户自选 symbol 列表(localStorage 持久化,跨会话不跨设备)。
 *
 * 用途:行情页头部"♥ 自选(N)"按钮展开列表 → 点击切 sel;⌘K"自选"分组快切。
 *
 * honest TD:localStorage MVP **不跨设备**。正经做法后端 watchlist 表
 * (user_id / exchange / market_type / symbol / position + mapper + controller + JaCoCo 测试)
 * 留账,跨设备同步正道。当前 MVP 够用(单设备偏好持久)。
 *
 * 多交易所归属:watchlist symbol 可能不属于当前基准交易所(切账户换 exchange),
 * 消费方(♥ 自选列表 / ⌘K 自选分组)应 filter 或提示"该交易所无此标的"(留账,当前直接列)。
 */
interface WatchlistState {
  symbols: string[]
  add: (symbol: string) => void
  remove: (symbol: string) => void
  has: (symbol: string) => boolean
  clear: () => void
}

export const useWatchlistStore = create<WatchlistState>()(
  persist(
    (set, get) => ({
      symbols: [],
      add: (symbol) =>
        set((s) => (s.symbols.includes(symbol) ? s : { symbols: [...s.symbols, symbol] })),
      remove: (symbol) => set((s) => ({ symbols: s.symbols.filter((s) => s !== symbol) })),
      has: (symbol) => get().symbols.includes(symbol),
      clear: () => set({ symbols: [] }),
    }),
    { name: 'kwikquant-watchlist' },
  ),
)
