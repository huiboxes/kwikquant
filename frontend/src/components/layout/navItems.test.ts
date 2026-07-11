import { describe, it, expect } from 'vitest'
import { NAV_ITEMS, NAV_GROUPS } from './navItems'

describe('navItems', () => {
  it('两组各含预期项(主线旅程 + 监控与管理)', () => {
    const main = NAV_ITEMS.filter((i) => i.group === '主线旅程')
    const mgmt = NAV_ITEMS.filter((i) => i.group === '监控与管理')
    expect(main.map((i) => i.id)).toEqual(['dashboard', 'strategy', 'backtest', 'trade'])
    expect(mgmt.map((i) => i.id)).toEqual(['portfolio', 'market', 'risk', 'history', 'settings'])
  })

  it('trade 项存在且 to 以 / 开头', () => {
    const trade = NAV_ITEMS.find((i) => i.id === 'trade')
    expect(trade).toBeDefined()
    expect(trade!.to.startsWith('/')).toBe(true)
  })

  it('所有项 to 以 / 开头 + icon 真实', () => {
    expect(NAV_GROUPS).toEqual(['主线旅程', '监控与管理'])
    for (const it of NAV_ITEMS) {
      expect(it.to.startsWith('/')).toBe(true)
      expect(it.icon).toBeTruthy()
    }
  })
})
