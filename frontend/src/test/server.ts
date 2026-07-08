import { setupServer } from 'msw/node'

/**
 * MSW 测试 server。
 * setup.ts 在 beforeAll 启动 / afterEach resetHandlers / afterAll close。
 * handlers 在重做阶段按业务补回。
 */
export const server = setupServer()
