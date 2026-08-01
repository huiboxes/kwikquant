import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MarkdownText } from './MarkdownText'

/**
 * MarkdownText 测试 — 自建 markdown 渲染(弃 assistant-ui MarkdownTextPrimitive)。
 *
 * 覆盖:标题/段落/列表/inline code/block code(语言标签+复制按钮)/无 lang fence。
 * navigator.clipboard 在 jsdom 不存在,beforeEach 注入 mock。
 */

type WriteText = (text: string) => Promise<void>
interface ClipboardMock {
  writeText: ReturnType<typeof vi.fn>
}

describe('MarkdownText', () => {
  beforeEach(() => {
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } satisfies ClipboardMock,
    })
  })

  it('渲染标题/段落/列表', () => {
    const { container } = render(<MarkdownText text={'# 标题\n\n段落\n\n- 项1\n- 项2'} />)
    expect(container.querySelector('h1')?.textContent).toBe('标题')
    expect(container.querySelectorAll('ul li')).toHaveLength(2)
  })

  it('inline code 渲染为 <code>(不进 CodeBlock)', () => {
    const { container } = render(<MarkdownText text={'用 `foo()` 函数'} />)
    const code = container.querySelector('p code')
    expect(code?.textContent).toBe('foo()')
    // inline code 不应触发 CodeBlock(无复制按钮)
    expect(screen.queryByRole('button', { name: '复制代码' })).not.toBeInTheDocument()
  })

  it('代码块渲染 CodeBlock:语言标签 + 复制按钮', () => {
    render(<MarkdownText text={'```python\ndef f():\n    pass\n```'} />)
    expect(screen.getByText('python')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '复制代码' })).toBeInTheDocument()
  })

  it('复制按钮点击 → clipboard.writeText + 显示已复制', async () => {
    render(<MarkdownText text={'```python\nprint(1)\n```'} />)
    fireEvent.click(screen.getByRole('button', { name: '复制代码' }))
    const writeText = (navigator as unknown as { clipboard: { writeText: WriteText } })
      .clipboard.writeText
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('print(1)'))
    await waitFor(() => expect(screen.getByText('已复制')).toBeInTheDocument())
  })

  it('无 fence lang 代码块显示 code 标签', () => {
    render(<MarkdownText text={'```\nplain\n```'} />)
    expect(screen.getByText('code')).toBeInTheDocument()
  })

  it('表格渲染', () => {
    const { container } = render(
      <MarkdownText text={'| a | b |\n| - | - |\n| 1 | 2 |'} />,
    )
    expect(container.querySelectorAll('th')).toHaveLength(2)
    expect(container.querySelectorAll('td')).toHaveLength(2)
  })
})
