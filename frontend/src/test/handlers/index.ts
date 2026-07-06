import { authHandlers } from './auth'
import { strategyHandlers } from './strategies'
import { aiHandlers } from './ai'
import { aiKeyHandlers } from './aiKeys'
import { codeHandlers } from './codes'
import { backtestHandlers } from './backtests'
import { reportHandlers } from './reports'

/**
 * 批 1a + 1b MSW handler 入口(spec §5 step 6/8/14/15-16 + step 19-23)。
 * 批 1a:auth + strategies + ai-keys + ai-chat(SSE) + codes(契约 A) + publish。
 * 批 1b:backtests(POST/GET 轮询) + reports(详情)。
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
