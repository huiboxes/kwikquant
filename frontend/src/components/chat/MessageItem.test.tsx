import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MessageItem } from './MessageItem'
import type { StoreMessage } from '@/hooks/useAssistantChat'

/**
 * MessageItem 测试 — 自建消息渲染(user 浅底右对齐 / assistant 全宽 + 光标 + 错误态)。
 */

function userMsg(overrides: Partial<StoreMessage> = {}): StoreMessage {
  return { id: 'u1', role: 'user', content: '帮我改进', ts: '10:38', ...overrides }
}
function aiMsg(overrides: Partial<StoreMessage> = {}): StoreMessage {
  return { id: 'a1', role: 'assistant', content: '好的', ts: '10:39', ...overrides }
}

describe('MessageItem', () => {
  it('user 消息渲染 content + ts', () => {
    render(<MessageItem message={userMsg()} />)
    expect(screen.getByText('帮我改进')).toBeInTheDocument()
    expect(screen.getByText('10:38')).toBeInTheDocument()
  })

  it('assistant 有 content 渲染 markdown + AI 头像', () => {
    render(<MessageItem message={aiMsg({ content: '**粗体**' })} />)
    // markdown strong 渲染
    expect(screen.getByText('粗体').tagName).toBe('STRONG')
    // AI 方块头像
    expect(screen.getAllByText('AI').length).toBeGreaterThan(0)
  })

  it('assistant streaming + 有 content → 显示光标 ▍', () => {
    render(<MessageItem message={aiMsg({ content: '部分回复' })} isStreaming />)
    expect(screen.getByText('▍')).toBeInTheDocument()
  })

  it('assistant 非 streaming → 不显示光标', () => {
    render(<MessageItem message={aiMsg({ content: '完成' })} />)
    expect(screen.queryByText('▍')).not.toBeInTheDocument()
  })

  it('assistant streaming + 空 content → 显示 正在思考…', () => {
    render(<MessageItem message={aiMsg({ content: '' })} isStreaming />)
    expect(screen.getByText('正在思考…')).toBeInTheDocument()
  })

  it('assistant error → 显示错误文本 + 重试按钮', () => {
    render(
      <MessageItem
        message={aiMsg({ content: '', error: '连接超时' })}
        onRetry={vi.fn()}
      />,
    )
    expect(screen.getByText('连接超时')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })

  it('重试按钮 click → onRetry 调用', () => {
    const onRetry = vi.fn()
    render(
      <MessageItem message={aiMsg({ content: '', error: '超时' })} onRetry={onRetry} />,
    )
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('assistant 无 content 无 error 非 streaming → 不留空(无光标/无思考态)', () => {
    render(<MessageItem message={aiMsg({ content: '' })} />)
    expect(screen.queryByText('▍')).not.toBeInTheDocument()
    expect(screen.queryByText('正在思考…')).not.toBeInTheDocument()
  })
})
