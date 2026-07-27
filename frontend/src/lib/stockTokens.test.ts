import { describe, it, expect } from 'vitest'
import { isStockToken, canSwitchMarketType } from './stockTokens'

/**
 * isStockToken — 判断 canonical symbol 是否 OKX 股票代币(Underified Tokenized Stocks)。
 *
 * 关键约束:零误判(加密币/贵金属即使 X 前缀也必须判 false,否则 XRP 被标「股」用户困惑)。
 * 漏标(未确认的股票代币没标)可接受 — 漏标只是没标记,用户切 PERP 才发现,非本方案引入。
 */
describe('isStockToken', () => {
  it('OKX 公告 Batch 标的判 true', () => {
    expect(isStockToken('XAAPL/USDT')).toBe(true) // Apple
    expect(isStockToken('XGOOGL/USDT')).toBe(true) // Alphabet
    expect(isStockToken('XTSLA/USDT')).toBe(true) // Tesla
    expect(isStockToken('XMU/USDT')).toBe(true) // Micron(Batch 1)
  })

  it('加密币判 false(关键:X 开头加密币不误判为股票)', () => {
    expect(isStockToken('XRP/USDT')).toBe(false) // Ripple
    expect(isStockToken('XLM/USDT')).toBe(false) // Stellar
    expect(isStockToken('XTZ/USDT')).toBe(false) // Tezos
    expect(isStockToken('XCH/USDT')).toBe(false) // Chia
    expect(isStockToken('XAUT/USDT')).toBe(false) // Tether Gold(代币化黄金,非股票)
  })

  it('贵金属 ISO 代码判 false', () => {
    expect(isStockToken('XAG/USDT')).toBe(false) // 白银
    expect(isStockToken('XAU/USDT')).toBe(false) // 黄金
    expect(isStockToken('XPT/USDT')).toBe(false) // 铂
    expect(isStockToken('XPD/USDT')).toBe(false) // 钯
    expect(isStockToken('XCU/USDT')).toBe(false) // 铜
  })

  it('主流加密币判 false', () => {
    expect(isStockToken('BTC/USDT')).toBe(false)
    expect(isStockToken('ETH/USDT')).toBe(false)
    expect(isStockToken('SOL/USDT')).toBe(false)
  })

  it('空值/异常输入判 false', () => {
    expect(isStockToken(undefined)).toBe(false)
    expect(isStockToken(null)).toBe(false)
    expect(isStockToken('')).toBe(false)
    expect(isStockToken('USDT')).toBe(false)
  })

  it('无 / 分隔的 base 也能判断', () => {
    expect(isStockToken('XAAPL')).toBe(true)
    expect(isStockToken('BTC')).toBe(false)
    expect(isStockToken('XRP')).toBe(false)
  })
})

describe('canSwitchMarketType', () => {
  it('股票代币禁切 PERP(OKX 无股票合约)', () => {
    expect(canSwitchMarketType('XAAPL/USDT', 'PERP')).toBe(false)
    expect(canSwitchMarketType('XTSLA/USDT', 'PERP')).toBe(false)
  })

  it('股票代币可切 SPOT', () => {
    expect(canSwitchMarketType('XAAPL/USDT', 'SPOT')).toBe(true)
  })

  it('加密币可切 PERP(关键:不误拦 XRP 等加密币)', () => {
    expect(canSwitchMarketType('XRP/USDT', 'PERP')).toBe(true)
    expect(canSwitchMarketType('BTC/USDT', 'PERP')).toBe(true)
    expect(canSwitchMarketType('ETH/USDT', 'PERP')).toBe(true)
  })

  it('空值兜底不拦(允许切)', () => {
    expect(canSwitchMarketType(undefined, 'PERP')).toBe(true)
    expect(canSwitchMarketType('', 'PERP')).toBe(true)
  })
})
