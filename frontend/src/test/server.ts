import { setupServer } from 'msw/node'
import { authHandlers } from './handlers/auth'
import { tradeHistoryHandlers } from './handlers/trade-history'
import { riskHandlers } from './handlers/risk'
import { strategyHandlers } from './handlers/strategy'
import { accountHandlers } from './handlers/account'
import { portfolioHandlers } from './handlers/portfolio'
import { marketHandlers } from './handlers/market'
import { backtestHandlers } from './handlers/backtest'

/**
 * MSW 测试 server。
 * setup.ts 在 beforeAll 启动 / afterEach resetHandlers / afterAll close。
 * handlers 按业务补回(按页驱动追加:auth + trade-history + risk + strategy + account + portfolio + market + backtest)。
 */
export const server = setupServer(
  ...authHandlers,
  ...tradeHistoryHandlers,
  ...riskHandlers,
  ...strategyHandlers,
  ...accountHandlers,
  ...portfolioHandlers,
  ...marketHandlers,
  ...backtestHandlers,
)
