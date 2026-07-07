import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AISidebar } from './AISidebar'

vi.mock('@/hooks/useAiKeys', () => ({
  useAiKeys: () => ({
    data: [
      {
        id: 1,
        label: '主 key',
        provider: 'OPENAI',
        apiKeyMasked: '...6xyz',
        baseUrl: null,
        createdAt: '',
      },
    ],
    isLoading: false,
  }),
}))

const { mockStore } = vi.hoisted(() => ({
  mockStore: {
    messages: [],
    streaming: false,
    llmKeyId: 1,
    setLlmKeyId: vi.fn(),
    setStreaming: vi.fn(),
    addUserMessage: vi.fn(),
    startAssistant: vi.fn(),
    appendToLastAssistant: vi.fn(),
    markLastAssistantError: vi.fn(),
    getState: () => ({
      messages: [],
      streaming: false,
      llmKeyId: 1,
      addUserMessage: vi.fn(),
      startAssistant: vi.fn(),
      appendToLastAssistant: vi.fn(),
      markLastAssistantError: vi.fn(),
      setStreaming: vi.fn(),
    }),
  },
}))
vi.mock('@/stores/aiChatStore', () => ({
  useAiChatStore: () => mockStore,
}))

vi.mock('@/lib/sse', () => ({
  streamChat: vi.fn(),
}))

const wrap = (initial = '/strategies/1') =>
  ({ children }: { children: React.ReactNode }) => (
    <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>
  )

describe('AISidebar 增强', () => {
  it('LLM key picker 是 shadcn Select(非原生 select,无 option)', () => {
    render(<AISidebar />, { wrapper: wrap() })
    // 原生 select 有 role=option;shadcn Select 未点开时无 SelectItem
    expect(screen.queryByRole('option')).not.toBeInTheDocument()
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })

  it('显 provider 标签(OPENAI)', () => {
    render(<AISidebar />, { wrapper: wrap() })
    expect(screen.getByText('OPENAI')).toBeInTheDocument()
  })

  it('渲染 4 提示卡片', () => {
    render(<AISidebar />, { wrapper: wrap() })
    expect(screen.getByText('帮我加追踪止损 5%')).toBeInTheDocument()
    expect(screen.getByText('解释这段策略代码')).toBeInTheDocument()
    expect(screen.getByText('优化最大回撤')).toBeInTheDocument()
    expect(screen.getByText('加仓位管理')).toBeInTheDocument()
  })

  it('点提示卡片填输入框(前 1 个不自动发送)', () => {
    render(<AISidebar />, { wrapper: wrap() })
    fireEvent.click(screen.getByText('帮我加追踪止损 5%'))
    expect(
      screen.getByDisplayValue('帮我加追踪止损 5%'),
    ).toBeInTheDocument()
  })
})
