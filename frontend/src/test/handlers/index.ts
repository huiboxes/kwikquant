import { authHandlers } from './auth'
import { strategyHandlers } from './strategies'
import { aiHandlers } from './ai'
import { aiKeyHandlers } from './aiKeys'
import { codeHandlers } from './codes'

/**
 * 批 1a MSW handler 入口(spec §5 step 6/8/14/15-16)。
 * 批 1a 端点:auth + strategies + ai-keys + ai-chat(SSE) + codes(契约 A) + publish。
 */
export const handlers = [
  ...authHandlers,
  ...strategyHandlers,
  ...aiKeyHandlers,
  ...aiHandlers,
  ...codeHandlers,
]
