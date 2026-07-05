import { create } from 'zustand'

/**
 * WebSocket 连接状态 store(spec §5 step 9,F12.6)。
 *
 * 三态:connected(已连接) / reconnecting(重连中) / failed(已断开,重连耗尽或握手失败)。
 * WsConnectionIndicator 接此 store 渲染 🟢/🟡/🔴 + 断连 Banner。
 *
 * attempt:当前重连次数(供 tooltip 显示"重连 N 次")。
 * lastConnectedAt:上次成功连接时间戳(供 tooltip 显示"上次连接时间")。
 */
export type WsStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'failed'

interface WsState {
  status: WsStatus
  attempt: number
  lastConnectedAt: number | null
  lastError: string | null
  setStatus: (status: WsStatus) => void
  setAttempt: (n: number) => void
  incAttempt: () => void
  markConnected: () => void
  markError: (msg: string) => void
  reset: () => void
}

export const useWsStore = create<WsState>((set) => ({
  status: 'idle',
  attempt: 0,
  lastConnectedAt: null,
  lastError: null,

  setStatus: (status) => set({ status }),
  setAttempt: (n) => set({ attempt: n }),
  incAttempt: () => set((s) => ({ attempt: s.attempt + 1 })),
  markConnected: () =>
    set({ status: 'connected', lastConnectedAt: Date.now(), attempt: 0, lastError: null }),
  markError: (msg) => set({ lastError: msg }),
  reset: () => set({ status: 'idle', attempt: 0, lastError: null }),
}))
