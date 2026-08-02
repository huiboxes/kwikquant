import { describe, it, expect } from 'vitest'
import { fetchBacktestList, listBacktestTasks } from '../api/backtest'

describe('fetchBacktestList', () => {
  it('returns all backtests with totalReturn + strategyName (COMPLETED + RUNNING)', async () => {
    const list = await fetchBacktestList()
    expect(list.length).toBeGreaterThanOrEqual(3)
    const completed = list.find((t) => t.id === 2201)!
    expect(completed.status).toBe('COMPLETED')
    expect(completed.totalReturn).toBe(0.1532)
    expect(completed.strategyName).toBe('BTC Trend Rider v1.3.2')
    expect(completed.reportId).toBe(1)
    const running = list.find((t) => t.id === 2203)!
    expect(running.status).toBe('RUNNING')
    expect(running.totalReturn).toBeNull()
    expect(running.reportId).toBeNull()
    expect(running.processedBars).toBe(4400)
    expect(running.totalBars).toBe(8760)
  })
})

describe('listBacktestTasks', () => {
  it('filters by strategyId', async () => {
    const list = await listBacktestTasks(10)
    expect(list.length).toBe(1)
    expect(list.every((t) => t.strategyId === 10)).toBe(true)
  })
})
