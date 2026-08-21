import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * UI 会话态(默认不 persist——每次刷新重置；tradeMode 例外，见下)。
 *
 * 对应原型 AppContext 的会话态子集:
 * - cmdOpen / notifOpen:命令面板 / 通知抽屉开关
 * - tradeMode:'PAPER'(默认，模拟盘)| 'LIVE'(实盘)——交易页强区分用。
 *   persist 到 localStorage(key=kwikquant-ui):全系统读 API 以 mode 为口径后，
 *   刷新/新标签页需保持用户所选视图模式，避免回退默认造成数据口径跳变。
 * - liveConfirmedThisSession:本会话是否已确认切到 LIVE(确认过就不再弹，不 persist)
 * - exchange:当前会话选中的交易所(默认 'OKX' 项目基准，对齐后端 application.yaml
 *   exchanges 首位 + AuthService 注册建 OKX 模拟盘)。CreateStrategyDialog /
 *   AddAccountDialog / StrategyPage 回测共享此单一来源，避免各自 local state 默认值
 *   分裂(原 CreateStrategyDialog 硬编码 BINANCE / AddAccountDialog useState BINANCE
 *   / StrategyPage useState OKX 三份不一致)。注意：策略记录的 exchange 字段是创建时
 *   落库的持久化值，与此会话态不同——改策略 exchange 走 fork 新策略。
 */
export type TradeMode = 'PAPER' | 'LIVE'
export type Exchange = 'BINANCE' | 'OKX' | 'BITGET'

interface UiState {
  cmdOpen: boolean
  setCmdOpen: (v: boolean) => void
  notifOpen: boolean
  setNotifOpen: (v: boolean) => void
  mobileNavOpen: boolean
  setMobileNavOpen: (v: boolean) => void
  tradeMode: TradeMode
  setTradeMode: (m: TradeMode) => void
  liveConfirmedThisSession: boolean
  setLiveConfirmedThisSession: (v: boolean) => void
  exchange: Exchange
  setExchange: (e: Exchange) => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      cmdOpen: false,
      setCmdOpen: (v) => set({ cmdOpen: v }),
      notifOpen: false,
      setNotifOpen: (v) => set({ notifOpen: v }),
      mobileNavOpen: false,
      setMobileNavOpen: (v) => set({ mobileNavOpen: v }),
      tradeMode: 'PAPER',
      setTradeMode: (m) => set({ tradeMode: m }),
      liveConfirmedThisSession: false,
      setLiveConfirmedThisSession: (v) => set({ liveConfirmedThisSession: v }),
      exchange: 'OKX',
      setExchange: (e) => set({ exchange: e }),
    }),
    {
      name: 'kwikquant-ui',
      partialize: (s) => ({ tradeMode: s.tradeMode }),
    },
  ),
)

// 跨标签页同步：另一 tab 切 PAPER/LIVE 时(storage 事件)本 tab 重读持久化值。
// 否则 A 切实盘 B 仍显模拟，多标签页(交易场景常见)会误读当前模式口径。
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === 'kwikquant-ui') {
      void useUiStore.persist.rehydrate()
    }
  })
}
