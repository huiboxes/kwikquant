import { create } from 'zustand'

/**
 * UI 会话态(不 persist——每次刷新重置)。
 *
 * 对应原型 AppContext 的会话态子集:
 * - cmdOpen / notifOpen:命令面板 / 通知抽屉开关
 * - tradeMode:'PAPER'(默认,模拟盘)| 'LIVE'(实盘)——交易页强区分用
 * - liveConfirmedThisSession:本会话是否已确认切到 LIVE(确认过就不再弹)
 */
export type TradeMode = 'PAPER' | 'LIVE'

interface UiState {
  cmdOpen: boolean
  setCmdOpen: (v: boolean) => void
  notifOpen: boolean
  setNotifOpen: (v: boolean) => void
  tradeMode: TradeMode
  setTradeMode: (m: TradeMode) => void
  liveConfirmedThisSession: boolean
  setLiveConfirmedThisSession: (v: boolean) => void
}

export const useUiStore = create<UiState>((set) => ({
  cmdOpen: false,
  setCmdOpen: (v) => set({ cmdOpen: v }),
  notifOpen: false,
  setNotifOpen: (v) => set({ notifOpen: v }),
  tradeMode: 'PAPER',
  setTradeMode: (m) => set({ tradeMode: m }),
  liveConfirmedThisSession: false,
  setLiveConfirmedThisSession: (v) => set({ liveConfirmedThisSession: v }),
}))
