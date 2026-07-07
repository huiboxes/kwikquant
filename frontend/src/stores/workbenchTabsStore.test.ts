import { describe, it, expect, beforeEach } from 'vitest'
import { useWorkbenchTabsStore } from './workbenchTabsStore'

describe('workbenchTabsStore', () => {
  beforeEach(() => {
    useWorkbenchTabsStore.setState({ drafts: {}, lastTaskIds: {} })
  })

  it('setDraft 写入草稿', () => {
    useWorkbenchTabsStore.getState().setDraft(2, 'print(1)')
    expect(useWorkbenchTabsStore.getState().drafts[2]).toBe('print(1)')
  })

  it('setDraft 覆盖同 strategyId 旧草稿', () => {
    useWorkbenchTabsStore.getState().setDraft(2, 'a')
    useWorkbenchTabsStore.getState().setDraft(2, 'b')
    expect(useWorkbenchTabsStore.getState().drafts[2]).toBe('b')
  })

  it('setLastTaskId 写入最近 taskId', () => {
    useWorkbenchTabsStore.getState().setLastTaskId(2, 99)
    expect(useWorkbenchTabsStore.getState().lastTaskIds[2]).toBe(99)
  })

  it('clearDraft 移除指定 strategyId 草稿', () => {
    useWorkbenchTabsStore.getState().setDraft(2, 'x')
    useWorkbenchTabsStore.getState().setDraft(3, 'y')
    useWorkbenchTabsStore.getState().clearDraft(2)
    expect(useWorkbenchTabsStore.getState().drafts[2]).toBeUndefined()
    expect(useWorkbenchTabsStore.getState().drafts[3]).toBe('y')
  })
})
