import { setupServer } from 'msw/node'
import { authHandlers } from './handlers/auth'
import { tradeHistoryHandlers } from './handlers/trade-history'
import { riskHandlers } from './handlers/risk'
import { strategyHandlers } from './handlers/strategy'
import { accountHandlers } from './handlers/account'
import { portfolioHandlers } from './handlers/portfolio'
import { marketHandlers } from './handlers/market'
import { backtestHandlers } from './handlers/backtest'
import { settingsHandlers } from './handlers/settings'
import { tradingHandlers } from './handlers/trading'
import { aiChatHandlers } from './handlers/ai-chat'
import { templateHandlers } from './handlers/template'

/**
 * MSW 测试 server。
 * setup.ts 在 beforeAll 启动 / afterEach resetHandlers / afterAll close。
 * handlers 按业务补回(按页驱动追加:auth + trade-history + risk + strategy + account + portfolio + market + backtest + settings + trading + ai-chat + template)。
 */
export const server = setupServer(
  ...authHandlers,
  ...tradeHistoryHandlers,
  ...riskHandlers,
  // template 注册在 strategy 之前:MSW 按注册顺序匹配，strategy 的 /strategies/:id 会吞掉
  // /strategies/templates 字面路径(后端 Spring 是字面段优先，MSW 无此语义，靠顺序补偿)。
  ...templateHandlers,
  ...strategyHandlers,
  ...accountHandlers,
  ...portfolioHandlers,
  ...marketHandlers,
  ...backtestHandlers,
  ...settingsHandlers,
  ...tradingHandlers,
  ...aiChatHandlers,
)
