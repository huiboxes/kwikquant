import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, afterAll, beforeAll } from 'vitest'
import { server } from './server'
import { resetBacktestState } from './handlers/backtests'

// MSW:测试前启动 server,每用例后重置 handler(防 spy handler 泄漏) + backtests 模块状态
// (防 POLL_COUNTS 累积致同文件测试顺序耦合),全部结束关闭。
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetBacktestState()
  cleanup()
})
afterAll(() => server.close())
