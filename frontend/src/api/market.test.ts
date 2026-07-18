import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { fetchOrderBook } from './market'
import { useAuthStore } from '@/stores/authStore'
import { server } from '@/test/server'

describe('fetchOrderBook', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'test-token',
      user: { userId: 1, username: 'tester' },
    })
  })

  it('带 Bearer + symbol URL 编码(/ → -)+ depth query + 解析 bids/asks', async () => {
    let capturedAuth: string | null = null
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/market/orderbook/:exchange/:marketType/:symbol', ({ request }) => {
        capturedAuth = request.headers.get('Authorization')
        capturedUrl = request.url
        return HttpResponse.json(
          {
            code: 0,
            message: 'ok',
            data: {
              exchange: 'BINANCE',
              marketType: 'SPOT',
              symbol: 'BTC/USDT',
              bids: [
                { price: 61200, qty: 0.5 },
                { price: 61190, qty: 0.3 },
              ],
              asks: [{ price: 61250, qty: 0.4 }],
              timestamp: '2026-07-18T10:00:00Z',
              receivedAt: '2026-07-18T10:00:01Z',
            },
            traceId: 't1',
          },
          { status: 200 },
        )
      }),
    )
    const r = await fetchOrderBook('BINANCE', 'SPOT', 'BTC/USDT', 20)
    expect(capturedUrl).toContain('/market/orderbook/BINANCE/SPOT/BTC-USDT')
    expect(capturedUrl).toContain('depth=20')
    expect(capturedAuth).toBe('Bearer test-token')
    expect(r.bids?.length).toBe(2)
    expect(r.asks?.[0]?.price).toBe(61250)
  })

  it('无 depth → 不带 query,空盘口', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/market/orderbook/:exchange/:marketType/:symbol', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(
          { code: 0, message: 'ok', data: { bids: [], asks: [] }, traceId: 't2' },
          { status: 200 },
        )
      }),
    )
    const r = await fetchOrderBook('OKX', 'SPOT', 'ETH/USDT')
    expect(capturedUrl).toContain('/OKX/SPOT/ETH-USDT')
    expect(capturedUrl).not.toContain('depth=')
    expect(r.bids).toEqual([])
    expect(r.asks).toEqual([])
  })
})
