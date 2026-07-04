import { describe, expect, it } from 'vitest'

/**
 * Step 1 冒烟测试：只验证 Vitest 基建可跑通（能 import、能断言、setup 已加载）。
 * Step 5 起会有真实的纯函数测试。
 */
describe('vitest smoke', () => {
  it('runs and asserts', () => {
    expect(1 + 1).toBe(2)
  })
})
