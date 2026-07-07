import { useSearchParams } from 'react-router-dom'

/**
 * useWorkbenchTabs — URL ?tabs=&active= 是多 tab 真相源。
 *
 * tabs: 逗号分隔 strategyId 列表;active: 当前激活 strategyId(不在 tabs 内回退 tabs[0])。
 * addTab/removeTab/setActive/initIfEmpty 都 setSearchParams 同步 URL。
 */
export function useWorkbenchTabs() {
  const [params, setParams] = useSearchParams()

  const tabs =
    params
      .get('tabs')
      ?.split(',')
      .map((s) => Number.parseInt(s, 10))
      .filter((n) => !Number.isNaN(n)) ?? []

  const activeRaw = Number.parseInt(params.get('active') ?? '', 10)
  const active =
    tabs.length === 0 ? null : tabs.includes(activeRaw) ? activeRaw : tabs[0]

  const writeParams = (nextTabs: number[], nextActive: number | null) => {
    const p = new URLSearchParams(params)
    if (nextTabs.length === 0) {
      p.delete('tabs')
      p.delete('active')
    } else {
      p.set('tabs', nextTabs.join(','))
      p.set('active', String(nextActive ?? nextTabs[0]))
    }
    setParams(p)
  }

  const addTab = (id: number) => {
    const next = tabs.includes(id) ? tabs : [...tabs, id]
    writeParams(next, id)
  }

  const removeTab = (id: number) => {
    const idx = tabs.indexOf(id)
    const next = tabs.filter((t) => t !== id)
    let nextActive: number | null
    if (active !== id) {
      nextActive = active
    } else if (next.length === 0) {
      nextActive = null
    } else {
      // 优先右邻居(idx 在 next 里同位置),无则左邻居(idx-1)
      const rightIdx = Math.min(idx, next.length - 1)
      nextActive = next[rightIdx] ?? next[idx - 1] ?? null
    }
    writeParams(next, nextActive)
  }

  const setActive = (id: number) => {
    if (tabs.includes(id)) writeParams(tabs, id)
  }

  const initIfEmpty = (id: number) => {
    if (tabs.length === 0) writeParams([id], id)
  }

  return { tabs, active, addTab, removeTab, setActive, initIfEmpty }
}
