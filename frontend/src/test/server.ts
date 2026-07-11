import { setupServer } from 'msw/node'
import { authHandlers } from './handlers/auth'

/**
 * MSW 测试 server。
 * setup.ts 在 beforeAll 启动 / afterEach resetHandlers / afterAll close。
 * handlers 按业务补回(当前:auth)。
 */
export const server = setupServer(...authHandlers)
