import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { EmptyRow, LoadingRow } from './table'

// EmptyRow/LoadingRow 渲染 tr>td，需在 table+tbody 内(否则 React 警告)
function renderInTable(ui: React.ReactNode) {
  return render(
    <table>
      <tbody>{ui}</tbody>
    </table>,
  )
}

/**
 * EmptyRow/LoadingRow 系统性修复测试。
 *
 * 根因:shadcn TableRow 默认 hover:bg-surface-hover(米色),empty/loading 行
 * hover 变米色 + EmptyState 白卡片 → "米色 padding 围白色 TD"(HistoryPage/
 * RiskPage 反复漏 hover:bg-transparent)。封装 EmptyRow/LoadingRow 一处定义
 * hover:bg-transparent，所有表格页用组件自动对齐。
 *
 * 本测试守卫:hover:bg-transparent 不能被误删(回归即测试红)。
 */
describe('EmptyRow / LoadingRow', () => {
  it('EmptyRow 渲染 hover:bg-transparent + colSpan + p-6(empty 行 hover 不变米色)', () => {
    const { container } = renderInTable(
      <EmptyRow colSpan={8}>
        <span>无数据</span>
      </EmptyRow>,
    )
    const tr = container.querySelector('tr')
    expect(tr?.className).toContain('hover:bg-transparent')
    const td = container.querySelector('td')
    expect(td?.getAttribute('colspan')).toBe('8')
    expect(td?.className).toContain('p-6')
  })

  it('LoadingRow 渲染 hover:bg-transparent + colSpan', () => {
    const { container } = renderInTable(
      <LoadingRow colSpan={5}>
        <span>加载中</span>
      </LoadingRow>,
    )
    const tr = container.querySelector('tr')
    expect(tr?.className).toContain('hover:bg-transparent')
    expect(container.querySelector('td')?.getAttribute('colspan')).toBe('5')
  })

  it('EmptyRow children 渲染在 td 内', () => {
    const { container } = renderInTable(
      <EmptyRow colSpan={3}>
        <span>占位文案</span>
      </EmptyRow>,
    )
    expect(container.querySelector('td')?.textContent).toContain('占位文案')
  })
})
