import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChatThread } from './ChatThread'
import type { StoreMessage } from '@/hooks/useAssistantChat'

/**
 * ChatThread 测试 — 自建消息列表(空态 Welcome / 非空渲染 / isStreaming 传递 / sticky-bottom 浮钮)。
 *
 * jsdom 无布局(scrollHeight/clientHeight=0),sticky-bottom 浮钮用 Object.defineProperty
 * mock 元素尺寸模拟"距底 >100px"。
 */

function msg(overrides: Partial<StoreMessage>): StoreMessage {
  return { id: '1', role: 'user', content: 'hi', ts: '10:00', ...overrides }
}

describe('ChatThread', () => {
  it('空态 messages=[] → Welcome 标题 + suggestions chips', () => {
    render(
      <ChatThread
        messages={[]}
        isRunning={false}
        suggestions={['加 ADX 过滤', '改止损']}
        onSuggestion={vi.fn()}
      />,
    )
    expect(screen.getByText('我可以帮你改进或调试策略')).toBeInTheDocument()
    expect(screen.getByText('加 ADX 过滤')).toBeInTheDocument()
    expect(screen.getByText('改止损')).toBeInTheDocument()
  })

  it('非空 messages → 渲染 MessageItem(不显 Welcome)', () => {
    render(
      <ChatThread
        messages={[
          msg({ id: '1', role: 'user', content: '用户问' }),
          msg({ id: '2', role: 'assistant', content: 'AI 答' }),
        ]}
        isRunning={false}
      />,
    )
    expect(screen.getByText('用户问')).toBeInTheDocument()
    expect(screen.getByText('AI 答')).toBeInTheDocument()
    expect(screen.queryByText('我可以帮你改进或调试策略')).not.toBeInTheDocument()
  })

  it('isRunning + last assistant → 传 isStreaming=true(显示 ▍ 光标)', () => {
    render(
      <ChatThread
        messages={[
          msg({ id: '1', role: 'user', content: '问' }),
          msg({ id: '2', role: 'assistant', content: '部分回复' }),
        ]}
        isRunning
      />,
    )
    expect(screen.getByText('▍')).toBeInTheDocument()
  })

  it('isRunning 但 last 是 user → 不传 isStreaming(无光标)', () => {
    render(
      <ChatThread
        messages={[
          msg({ id: '1', role: 'assistant', content: '答' }),
          msg({ id: '2', role: 'user', content: '问' }),
        ]}
        isRunning
      />,
    )
    expect(screen.queryByText('▍')).not.toBeInTheDocument()
  })

  it('suggestion chip click → onSuggestion(text)', () => {
    const onSuggestion = vi.fn()
    render(
      <ChatThread
        messages={[]}
        isRunning={false}
        suggestions={['建议 A']}
        onSuggestion={onSuggestion}
      />,
    )
    fireEvent.click(screen.getByText('建议 A'))
    expect(onSuggestion).toHaveBeenCalledWith('建议 A')
  })

  it('距底 >100px → 显示「新消息」浮钮;click → 滚到底 + 浮钮消失', () => {
    const { container } = render(
      <ChatThread messages={[msg({ id: '1', role: 'user', content: 'x' })]} isRunning={false} />,
    )
    const scrollEl = container.firstChild as HTMLDivElement
    Object.defineProperty(scrollEl, 'scrollHeight', { value: 500, configurable: true })
    Object.defineProperty(scrollEl, 'clientHeight', { value: 100, configurable: true })
    Object.defineProperty(scrollEl, 'scrollTop', {
      value: 0,
      configurable: true,
      writable: true,
    })
    fireEvent.scroll(scrollEl)
    // dist = 500 - 0 - 100 = 400 > 100 → 浮钮出现
    expect(screen.getByRole('button', { name: '滚动到最新消息' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '滚动到最新消息' }))
    // click 后 setIsAtBottom(true) → 浮钮消失
    expect(
      screen.queryByRole('button', { name: '滚动到最新消息' }),
    ).not.toBeInTheDocument()
  })

  it('距底 ≤100px → 不显示浮钮', () => {
    const { container } = render(
      <ChatThread messages={[msg({ id: '1', role: 'user', content: 'x' })]} isRunning={false} />,
    )
    const scrollEl = container.firstChild as HTMLDivElement
    Object.defineProperty(scrollEl, 'scrollHeight', { value: 200, configurable: true })
    Object.defineProperty(scrollEl, 'clientHeight', { value: 150, configurable: true })
    Object.defineProperty(scrollEl, 'scrollTop', {
      value: 0,
      configurable: true,
      writable: true,
    })
    fireEvent.scroll(scrollEl)
    // dist = 200 - 0 - 150 = 50 < 100 → 在底部,无浮钮
    expect(
      screen.queryByRole('button', { name: '滚动到最新消息' }),
    ).not.toBeInTheDocument()
  })
})
