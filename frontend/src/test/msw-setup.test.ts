import { describe, it, expect } from 'vitest'
import { apiFetch, ApiError } from '@/lib/http'

/**
 * MSW setup 链路验证(spec §5 step 6 verify):
 * 证明 MSW server.listen + handler + http.ts apiFetch + parseBody 双解包链路通。
 * 后续 step 10-16 的 hook 测试依赖此链路。
 */
describe('MSW setup 链路', () => {
  it('GET /strategies 返 mock list(parseBody 解 envelope)', async () => {
    const data = await apiFetch<{ id: number; name: string }[]>('/api/v1/strategies')
    expect(data).toHaveLength(2)
    expect(data[0].name).toBe('BTC 网格')
    expect(data[1].name).toBe('ETH 动量')
  })

  it('GET /strategies/:id 存在返详情', async () => {
    const data = await apiFetch<{ id: number; status: string }>('/api/v1/strategies/1')
    expect(data.id).toBe(1)
    expect(data.status).toBe('DRAFT')
  })

  it('GET /strategies/:id 不存在抛 ApiError(7001)', async () => {
    await expect(apiFetch('/api/v1/strategies/999')).rejects.toMatchObject({
      code: 7001,
    })
    await expect(apiFetch('/api/v1/strategies/999')).rejects.toBeInstanceOf(ApiError)
  })

  it('POST /strategies 创建返 201 + 新策略', async () => {
    const data = await apiFetch<{ id: number; status: string }>('/api/v1/strategies', {
      method: 'POST',
      body: { name: '测试策略', exchange: 'BINANCE', symbol: 'BTC/USDT' },
    })
    expect(data.id).toBe(3)
    expect(data.status).toBe('DRAFT')
  })
})
