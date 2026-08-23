import { createContext, useContext } from 'react'

/**
 * 策略工作台向 AI 代码块注入"应用到草稿"能力。
 * 无 provider(如其他聊天场景)时代码块只保留复制,不显应用按钮。
 */
export const CodeApplyContext = createContext<{
  onApplyCode: (code: string) => void
} | null>(null)

export function useCodeApply() {
  return useContext(CodeApplyContext)
}
