import { describe, it, expect } from 'vitest'
import { restartStrategy, stopStrategy } from '@/api/strategy'

/**
 * restartStrategy typed client 测(POST /restart;MSW handlers/strategy.ts 提供 restart handler)。
 * 验证 STOPPED→RUNNING + 非 STOPPED 源 7002 + typed client 接通 MSW。
 *
 * 注:handlers/strategy.ts 的 STRATEGIES 是模块级状态，stop/restart handler mutate 它。
 * 本文件内两测用不同 id(1 和 4)，状态不冲突；文件间 vitest 默认 isolate 模块缓存。
 */
describe('restartStrategy', () => {
  it('STOPPED 策略 → RUNNING(用已发布代码恢复运行)', async () => {
    // 前置:id=1 BTC Trend Rider 初始 RUNNING，先 stop 制造 STOPPED，再 restart
    const stopped = await stopStrategy(1)
    expect(stopped.status).toBe('STOPPED')
    const restarted = await restartStrategy(1, 1)
    expect(restarted.status).toBe('RUNNING')
    expect(restarted.id).toBe(1)
  })

  it('非 STOPPED 源 → 7002(id=4 PAUSED)', async () => {
    // id=4 Grid Scalper 初始 PAUSED,restart 应返 7002(状态不可转移)
    await expect(restartStrategy(4, 3)).rejects.toMatchObject({ code: 7002 })
  })
})
