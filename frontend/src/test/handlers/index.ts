import { authHandlers } from './auth'
import { strategyHandlers } from './strategies'
import { aiHandlers } from './ai'
import { aiKeyHandlers } from './aiKeys'
import { codeHandlers } from './codes'
import { backtestHandlers } from './backtests'
import { reportHandlers } from './reports'

/**
 *  + 1b MSW handler 入口。
 * :auth + strategies + ai-keys + ai-chat(SSE) + codes(契约 A) + publish。
 * :backtests(POST/GET 轮询) + reports(详情)。
 */
export const handlers = [
  ...authHandlers,
  ...strategyHandlers,
  ...aiKeyHandlers,
  ...aiHandlers,
  ...codeHandlers,
  ...backtestHandlers,
  ...reportHandlers,
]
