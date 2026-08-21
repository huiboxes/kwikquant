import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useMarketStore } from './marketStore'
import type { WsMessageHandler } from '@/lib/ws/ConnectionManager'

// mock ConnectionManager:subscribe 记 destination + 返 unsub(记调用的 destination)，验引用计数
// resetWsConnection 空 setup.ts afterEach 用(marketStore 不连真 WS)
const subscribeMock = vi.fn()
const unsubMock = vi.fn()
vi.mock('@/lib/ws/ConnectionManager', () => ({
  getWsConnection: () => ({
    subscribe: (dest: string, handler: WsMessageHandler) => {
      subscribeMock(dest)
      void handler
      return () => unsubMock(dest)
    },
  }),
  resetWsConnection: () => {},
}))

describe('marketStore subscribeTicker 引用计数', () => {
  beforeEach(() => {
    useMarketStore.getState().clearTicks()
    subscribeMock.mockClear()
    unsubMock.mockClear()
  })

  it('首次 subscribeTicker 发 SUBSCRIBE(destination = /topic/ticker/{ex}/{mt}/{sym-dash})', () => {
    useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'BTC/USDT')
    expect(subscribeMock).toHaveBeenCalledWith('/topic/ticker/OKX/SPOT/BTC-USDT')
  })

  it('多组件同 dest refCount++，只发一次 SUBSCRIBE(共享单订阅)', () => {
    useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'BTC/USDT')
    useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'BTC/USDT')
    expect(subscribeMock).toHaveBeenCalledTimes(1)
  })

  it('第一个 unmount 不退(refCount>0)，最后一个 unmount 才退(修原 Set 守卫误退缺陷)', () => {
    const unsub1 = useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'BTC/USDT')
    const unsub2 = useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'BTC/USDT')
    unsub1() // refCount 2→1，不退
    expect(unsubMock).not.toHaveBeenCalled()
    unsub2() // refCount 1→0，退
    expect(unsubMock).toHaveBeenCalledWith('/topic/ticker/OKX/SPOT/BTC-USDT')
  })

  it('不同 symbol 独立计数互不影响', () => {
    const unsubBtc = useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'BTC/USDT')
    const unsubEth = useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'ETH/USDT')
    expect(subscribeMock).toHaveBeenCalledTimes(2)
    unsubBtc()
    expect(unsubMock).toHaveBeenCalledWith('/topic/ticker/OKX/SPOT/BTC-USDT')
    // ETH 仍订(不该被 BTC 退影响)
    unsubEth()
    expect(unsubMock).toHaveBeenCalledWith('/topic/ticker/OKX/SPOT/ETH-USDT')
  })

  it('updateTick 更新 ticks 缓存(key = exchange:marketType:symbol 三元组)', () => {
    useMarketStore.getState().updateTick('OKX', 'SPOT', 'BTC/USDT', { symbol: 'BTC/USDT', last: '42000' } as never)
    expect(useMarketStore.getState().ticks['OKX:SPOT:BTC/USDT']?.last).toBe('42000')
  })

  it('B2: SPOT/PERP 同 symbol 不互相覆盖(ticks key 三元组隔离)', () => {
    useMarketStore.getState().updateTick('OKX', 'SPOT', 'BTC/USDT', { symbol: 'BTC/USDT', last: '42000' } as never)
    useMarketStore.getState().updateTick('OKX', 'PERP', 'BTC/USDT', { symbol: 'BTC/USDT', last: '43000' } as never)
    expect(useMarketStore.getState().ticks['OKX:SPOT:BTC/USDT']?.last).toBe('42000')
    expect(useMarketStore.getState().ticks['OKX:PERP:BTC/USDT']?.last).toBe('43000')
  })

  it('clearTicks 退所有订阅 + 清 ticks(测试清理)', () => {
    useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'BTC/USDT')
    useMarketStore.getState().subscribeTicker('OKX', 'SPOT', 'ETH/USDT')
    useMarketStore.getState().updateTick('OKX', 'SPOT', 'BTC/USDT', { symbol: 'BTC/USDT', last: '1' } as never)
    useMarketStore.getState().clearTicks()
    expect(unsubMock).toHaveBeenCalledTimes(2)
    expect(Object.keys(useMarketStore.getState().ticks)).toHaveLength(0)
  })

  it('subscribeTickers 批量订阅返 unsubAll(退全部)', () => {
    const unsubAll = useMarketStore.getState().subscribeTickers('OKX', 'SPOT', ['BTC/USDT', 'ETH/USDT'])
    expect(subscribeMock).toHaveBeenCalledTimes(2)
    unsubAll()
    expect(unsubMock).toHaveBeenCalledTimes(2)
  })
})
