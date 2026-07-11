import { setupServer } from 'msw/node'
import { authHandlers } from './handlers/auth'
import { tradeHistoryHandlers } from './handlers/trade-history'

/**
 * MSW 测试 server。
 * setup.ts 在 beforeAll 启动 / afterEach resetHandlers / afterAll close。
 * handlers 按业务补回(按页驱动追加:auth + trade-history)。
 */
export const server = setupServer(...authHandlers, ...tradeHistoryHandlers)
