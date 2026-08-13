import { describe, expect, it } from 'vitest'
import { prepareOrderIntent } from './orderIntent'

describe('prepareOrderIntent', () => {
  it('相同意图在超时重试时复用 clientOrderId', () => {
    const first = prepareOrderIntent(null, {
      symbol: 'BTC/USDT',
      amount: 1,
      clientOrderId: '',
      expireAt: '2026-09-01T00:00:00Z',
    })
    const retry = prepareOrderIntent(first, {
      symbol: 'BTC/USDT',
      amount: 1,
      clientOrderId: '',
      expireAt: '2026-09-01T00:00:00Z',
    })

    expect(retry).toBe(first)
    expect(retry.request.clientOrderId).toMatch(/^[0-9a-f-]{36}$/)
    expect(retry.request.expireAt).toBe('2026-09-01T00:00:00Z')
  })

  it('订单参数变化生成新意图', () => {
    const first = prepareOrderIntent(null, {
      symbol: 'BTC/USDT', amount: 1, clientOrderId: '', expireAt: '',
    })
    const changed = prepareOrderIntent(first, {
      symbol: 'BTC/USDT', amount: 2, clientOrderId: '', expireAt: '',
    })

    expect(changed.request.clientOrderId).not.toBe(first.request.clientOrderId)
    expect(changed.signature).not.toBe(first.signature)
  })
})
