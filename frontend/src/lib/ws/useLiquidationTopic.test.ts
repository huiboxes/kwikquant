import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useLiquidationTopic } from './useLiquidationTopic'
import type { WsLiquidation } from '@/types/ws'

// mock useWsTopic:截获 (destination, handler),给测试直接调 handler 触发
const useWsTopicMock = vi.fn<(dest: string | null, handler: (p: unknown) => void) => void>()
vi.mock('./useWsTopic', () => ({
  useWsTopic: (dest: string | null, handler: (p: unknown) => void) => useWsTopicMock(dest, handler),
}))

describe('useLiquidationTopic', () => {
  beforeEach(() => {
    useWsTopicMock.mockReset()
  })

  it('订阅 /topic/liquidations/{userId} 当 userId 存在', () => {
    const onLiq = vi.fn()
    renderHook(() => useLiquidationTopic(42, onLiq))

    expect(useWsTopicMock).toHaveBeenCalledTimes(1)
    const [dest, handler] = useWsTopicMock.mock.calls[0]!
    expect(dest).toBe('/topic/liquidations/42')
    // 触发 handler 验证回调透传 + 类型断言收窄
    const payload: WsLiquidation = {
      userId: 42,
      orderId: 99,
      accountId: 7,
      positionId: 128,
      positionSide: 'LONG',
      leverage: 10,
      liquidationPrice: 37105.0,
      markPrice: 42300.0,
      marginBalance: 40.0,
      realizedPnl: -2.5,
      reason: 'liquidation triggered at markPrice=42300.00',
      timestamp: '2026-07-21T08:00:00Z',
    }
    handler(payload)
    expect(onLiq).toHaveBeenCalledWith(payload)
  })

  it('userId 为 null 时不订阅(destination=null)', () => {
    const onLiq = vi.fn()
    renderHook(() => useLiquidationTopic(null, onLiq))

    expect(useWsTopicMock).toHaveBeenCalledTimes(1)
    const [dest] = useWsTopicMock.mock.calls[0]!
    expect(dest).toBeNull()
    expect(onLiq).not.toHaveBeenCalled()
  })

  it('userId 为空串时不订阅', () => {
    const onLiq = vi.fn()
    renderHook(() => useLiquidationTopic('', onLiq))

    const [dest] = useWsTopicMock.mock.calls[0]!
    expect(dest).toBeNull()
  })

  it('字符串 userId 也拼进 destination', () => {
    const onLiq = vi.fn()
    renderHook(() => useLiquidationTopic('42', onLiq))

    const [dest] = useWsTopicMock.mock.calls[0]!
    expect(dest).toBe('/topic/liquidations/42')
  })
})
