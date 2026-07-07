import { create } from 'zustand'

/**
 * workbenchTabsStore — 跨策略多 tab 的 per-strategy 运行时缓存。
 *
 * tab 列表 + active 不存这里（URL ?tabs=&active= 是真相源），
 * 这里只缓存 per-strategy 的: 编辑器本地草稿(未保存) + 最近回测 taskId。
 * AI 消息当前全局共享(useAiChatStore),per-strategyId 隔离留后续 wave(超 workbench 重写 scope)。
 */
export interface WorkbenchTabsState {
  drafts: Record<number, string>
  lastTaskIds: Record<number, number>
  setDraft: (strategyId: number, source: string) => void
  setLastTaskId: (strategyId: number, taskId: number) => void
  clearDraft: (strategyId: number) => void
}

export const useWorkbenchTabsStore = create<WorkbenchTabsState>((set) => ({
  drafts: {},
  lastTaskIds: {},
  setDraft: (strategyId, source) =>
    set((s) => ({ drafts: { ...s.drafts, [strategyId]: source } })),
  setLastTaskId: (strategyId, taskId) =>
    set((s) => ({ lastTaskIds: { ...s.lastTaskIds, [strategyId]: taskId } })),
  clearDraft: (strategyId) =>
    set((s) => {
      const next = { ...s.drafts }
      delete next[strategyId]
      return { drafts: next }
    }),
}))
