import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { useWorkbenchTabs } from './useWorkbenchTabs'

function wrapper({ initialPath = '/workbench' } = {}) {
  return ({ children }: { children: React.ReactNode }) => (
    <MemoryRouter initialEntries={[initialPath]}>{children}</MemoryRouter>
  )
}

describe('useWorkbenchTabs', () => {
  it('空 URL 返回 tabs=[] active=null', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), { wrapper: wrapper() })
    expect(result.current.tabs).toEqual([])
    expect(result.current.active).toBeNull()
  })

  it('解析 ?tabs=1,2,3&active=2', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1,2,3&active=2' }),
    })
    expect(result.current.tabs).toEqual([1, 2, 3])
    expect(result.current.active).toBe(2)
  })

  it('active 不在 tabs 内回退 tabs[0]', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1,2&active=99' }),
    })
    expect(result.current.active).toBe(1)
  })

  it('无效 tabId 过滤(非数字)', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1,abc,3&active=1' }),
    })
    expect(result.current.tabs).toEqual([1, 3])
  })

  it('addTab 追加 + 切 active(已存在只切 active)', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1,2&active=1' }),
    })
    act(() => result.current.addTab(3))
    expect(result.current.tabs).toEqual([1, 2, 3])
    expect(result.current.active).toBe(3)
    act(() => result.current.addTab(1))
    expect(result.current.tabs).toEqual([1, 2, 3])
    expect(result.current.active).toBe(1)
  })

  it('removeTab 移除 active 切右邻居', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1,2,3&active=2' }),
    })
    act(() => result.current.removeTab(2))
    expect(result.current.tabs).toEqual([1, 3])
    expect(result.current.active).toBe(3)
  })

  it('removeTab 移除 active 无右邻居切左', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1,2&active=2' }),
    })
    act(() => result.current.removeTab(2))
    expect(result.current.tabs).toEqual([1])
    expect(result.current.active).toBe(1)
  })

  it('removeTab 最后一个 active 切 null', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1&active=1' }),
    })
    act(() => result.current.removeTab(1))
    expect(result.current.tabs).toEqual([])
    expect(result.current.active).toBeNull()
  })

  it('setActive 必须已在 tabs 内才切换', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench?tabs=1,2&active=1' }),
    })
    act(() => result.current.setActive(99))
    expect(result.current.active).toBe(1)
    act(() => result.current.setActive(2))
    expect(result.current.active).toBe(2)
  })

  it('initIfEmpty 仅当 tabs 空时初始化', () => {
    const { result } = renderHook(() => useWorkbenchTabs(), {
      wrapper: wrapper({ initialPath: '/workbench' }),
    })
    act(() => result.current.initIfEmpty(5))
    expect(result.current.tabs).toEqual([5])
    expect(result.current.active).toBe(5)
    act(() => result.current.initIfEmpty(9))
    expect(result.current.tabs).toEqual([5])
  })
})
