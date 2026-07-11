import { describe, it, expect, beforeEach } from 'vitest'
import { useUiStore } from './uiStore'

describe('uiStore', () => {
  beforeEach(() => {
    useUiStore.setState({
      cmdOpen: false,
      notifOpen: false,
      mobileNavOpen: false,
      tradeMode: 'PAPER',
      liveConfirmedThisSession: false,
    })
  })

  it('cmdOpen 默认 false,setCmdOpen 切换', () => {
    expect(useUiStore.getState().cmdOpen).toBe(false)
    useUiStore.getState().setCmdOpen(true)
    expect(useUiStore.getState().cmdOpen).toBe(true)
  })

  it('tradeMode 默认 PAPER,setTradeMode 切 LIVE', () => {
    expect(useUiStore.getState().tradeMode).toBe('PAPER')
    useUiStore.getState().setTradeMode('LIVE')
    expect(useUiStore.getState().tradeMode).toBe('LIVE')
  })

  it('liveConfirmedThisSession 默认 false,setter 切换', () => {
    expect(useUiStore.getState().liveConfirmedThisSession).toBe(false)
    useUiStore.getState().setLiveConfirmedThisSession(true)
    expect(useUiStore.getState().liveConfirmedThisSession).toBe(true)
  })

  it('notifOpen 默认 false,setNotifOpen 切换', () => {
    expect(useUiStore.getState().notifOpen).toBe(false)
    useUiStore.getState().setNotifOpen(true)
    expect(useUiStore.getState().notifOpen).toBe(true)
  })
})
