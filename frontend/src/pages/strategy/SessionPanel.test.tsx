import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SessionPanel, type InterpretRequest } from './SessionPanel'
import type { UseAssistantChatReturn } from '@/hooks/useAssistantChat'
import type { LlmApiKeyView } from '@/api/ai'

// SessionPanel 的自动发问守卫单测:hook 层 mock 掉(useAssistantChat/useLlmKeys),
// 只验 interpretRequest 消费逻辑(strategyId 门控 / 无 key 引导 / nonce 防重发 / 流中等待)。
const { mockChat, mockKeys } = vi.hoisted(() => ({
  mockChat: vi.fn<() => Partial<UseAssistantChatReturn>>(() => ({
    messages: [],
    isRunning: false,
    model: '',
    setModel: vi.fn<(v: string) => void>(),
    codeSource: 'EDITOR',
    setCodeSource: vi.fn<(v: string) => void>(),
    onRun: vi.fn<(text: string, llmKeyId: number | null, opts?: { reportId?: number }) => void>(),
    onCancel: vi.fn<() => void>(),
    retryLast: vi.fn<() => void>(),
  })),
  mockKeys: vi.fn<() => { data: LlmApiKeyView[] | undefined; isSuccess: boolean }>(() => ({ data: undefined, isSuccess: false })),
}))

vi.mock('@/hooks/useAssistantChat', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useAssistantChat')>()
  return { ...actual, useAssistantChat: mockChat }
})
vi.mock('@/hooks/useSettings', () => ({ useLlmKeys: mockKeys }))

const strategy = {
  id: 5,
  name: 'MA 策略',
  description: '',
  symbol: 'BTC/USDT',
  exchange: 'BINANCE',
  marketType: 'SPOT',
  intervalValue: '1h',
  status: 'READY',
  parameters: '{}',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
} as never

const KEY: LlmApiKeyView = {
  id: 7,
  label: 'test key',
  provider: 'OPENAI',
  apiKeyMasked: '...abcd',
  baseUrl: '',
  availableModels: ['gpt-4o'],
  createdAt: '2026-01-01T00:00:00Z',
}

function renderPanel(props: Partial<React.ComponentProps<typeof SessionPanel>>) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <SessionPanel strategy={strategy} version={1} {...props} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

type OnRunFn = (text: string, llmKeyId: number | null, opts?: { reportId?: number }) => void

describe('SessionPanel AI 回测解读自动发问(P1)', () => {
  let onRun: Mock<OnRunFn>
  let onInterpretHandled: Mock<() => void>

  beforeEach(() => {
    vi.clearAllMocks()
    onRun = vi.fn<OnRunFn>()
    onInterpretHandled = vi.fn<() => void>()
    mockChat.mockReturnValue({
      messages: [],
      isRunning: false,
      model: 'gpt-4o',
      setModel: vi.fn<(v: string) => void>(),
      codeSource: 'EDITOR',
      setCodeSource: vi.fn<(v: string) => void>(),
      onRun,
      onCancel: vi.fn<() => void>(),
      retryLast: vi.fn<() => void>(),
    })
    mockKeys.mockReturnValue({ data: [KEY], isSuccess: true })
  })

  it('interpretRequest 策略匹配 + 有 key → 自动发固定问题并携带 reportId，随后回调消费', () => {
    renderPanel({
      interpretRequest: { reportId: 95, strategyId: 5, nonce: 1 },
      onInterpretHandled,
    })
    expect(onRun).toHaveBeenCalledTimes(1)
    expect(onRun).toHaveBeenCalledWith('请解读这次回测结果。', 7, { reportId: 95 })
    expect(onInterpretHandled).toHaveBeenCalledTimes(1)
  })

  it('strategyId 门控：目标策略与当前会话不一致 → 不发(等选中切过去)', () => {
    renderPanel({
      interpretRequest: { reportId: 95, strategyId: 999, nonce: 1 },
      onInterpretHandled,
    })
    expect(onRun).not.toHaveBeenCalled()
    expect(onInterpretHandled).not.toHaveBeenCalled()
  })

  it('strategyId=null(同页回测 tab 入口理论分支之外)→ 不门控，直接发', () => {
    renderPanel({
      interpretRequest: { reportId: 95, strategyId: null, nonce: 1 },
      onInterpretHandled,
    })
    expect(onRun).toHaveBeenCalledTimes(1)
  })

  it('同一 nonce 重渲染(父组件尚未清空)→ 不重复发问', () => {
    const req: InterpretRequest = { reportId: 95, strategyId: 5, nonce: 3 }
    const { rerender } = render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <SessionPanel strategy={strategy} version={1} interpretRequest={req} onInterpretHandled={onInterpretHandled} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    rerender(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <SessionPanel strategy={strategy} version={1} interpretRequest={req} onInterpretHandled={onInterpretHandled} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(onRun).toHaveBeenCalledTimes(1)
  })

  it('流式中(isRunning)→ 暂不发，等流结束再发', () => {
    mockChat.mockReturnValue({
      messages: [],
      isRunning: true,
      model: 'gpt-4o',
      setModel: vi.fn<(v: string) => void>(),
      codeSource: 'EDITOR',
      setCodeSource: vi.fn<(v: string) => void>(),
      onRun,
      onCancel: vi.fn<() => void>(),
      retryLast: vi.fn<() => void>(),
    })
    const req: InterpretRequest = { reportId: 95, strategyId: 5, nonce: 4 }
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { rerender } = render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <SessionPanel strategy={strategy} version={1} interpretRequest={req} onInterpretHandled={onInterpretHandled} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(onRun).not.toHaveBeenCalled()
    // 流结束(mockChat 返回 isRunning=false)→ effect 重触发，发问
    mockChat.mockReturnValue({
      messages: [],
      isRunning: false,
      model: 'gpt-4o',
      setModel: vi.fn<(v: string) => void>(),
      codeSource: 'EDITOR',
      setCodeSource: vi.fn<(v: string) => void>(),
      onRun,
      onCancel: vi.fn<() => void>(),
      retryLast: vi.fn<() => void>(),
    })
    rerender(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <SessionPanel strategy={strategy} version={1} interpretRequest={req} onInterpretHandled={onInterpretHandled} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(onRun).toHaveBeenCalledTimes(1)
  })

  it('无 LLM key → 不发问，引导提示 + 消费请求(BYO 引导，不吊死)', () => {
    mockKeys.mockReturnValue({ data: [], isSuccess: true })
    renderPanel({
      interpretRequest: { reportId: 95, strategyId: 5, nonce: 5 },
      onInterpretHandled,
    })
    expect(onRun).not.toHaveBeenCalled()
    expect(onInterpretHandled).toHaveBeenCalledTimes(1)
    // BYO 引导卡在场(去配置入口)
    expect(screen.getByText(/配置你的大模型密钥/)).toBeInTheDocument()
  })
})
