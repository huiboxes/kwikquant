import { setupServer } from 'msw/node'
import { handlers } from './handlers'

/**
 * MSW 测试 server(spec §5 step 6)。
 * setup.ts 在 beforeAll 启动 / afterEach resetHandlers / afterAll close。
 */
export const server = setupServer(...handlers)
