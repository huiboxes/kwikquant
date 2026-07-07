import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { renderMessageContent } from './aiMessageContent'

function renderContent(content: string) {
  return render(<>{renderMessageContent(content)}</>)
}

describe('renderMessageContent', () => {
  it('纯文本原样渲染', () => {
    const { container } = renderContent('普通文本')
    expect(container.textContent).toBe('普通文本')
  })

  it('反引号代码高亮为 <code>', () => {
    renderContent('已有 `on_bar` 函数')
    const code = screen.getByText('on_bar')
    expect(code.tagName).toBe('CODE')
    expect(code).toHaveClass('font-mono')
  })

  it('数值变化 X% → Y% 前红后绿', () => {
    renderContent('回撤 -8.7% → -6.2%')
    expect(screen.getByText('-8.7%')).toHaveClass('text-down')
    expect(screen.getByText('-6.2%')).toHaveClass('text-up')
  })

  it('HTML 标签作为文本(不执行,防 XSS)', () => {
    const { container } = renderContent('<script>alert(1)</script>')
    expect(container.textContent).toContain('<script>alert(1)</script>')
    expect(container.querySelector('script')).toBeNull()
  })

  it('混合: 代码 + 数值变化', () => {
    renderContent('`on_bar` 回撤 -8.7% → -6.2%')
    expect(screen.getByText('on_bar').tagName).toBe('CODE')
    expect(screen.getByText('-8.7%')).toHaveClass('text-down')
    expect(screen.getByText('-6.2%')).toHaveClass('text-up')
  })

  it('多个数值变化都高亮', () => {
    renderContent('收益 -5% → 10%, 回撤 -8% → -6%')
    expect(screen.getByText('-5%')).toHaveClass('text-down')
    expect(screen.getByText('10%')).toHaveClass('text-up')
    expect(screen.getByText('-8%')).toHaveClass('text-down')
    expect(screen.getByText('-6%')).toHaveClass('text-up')
  })

  it('无数值变化/无代码时纯文本', () => {
    const { container } = renderContent('策略已优化')
    expect(container.textContent).toBe('策略已优化')
  })
})
