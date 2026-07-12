import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { StrategyPage } from '@/pages/StrategyPage'

// Monaco 在 jsdom 不可用(canvas/WebWorker),mock 成一个 textarea
vi.mock('@monaco-editor/react', () => ({
  default: ({
    defaultValue,
    onChange,
  }: {
    defaultValue?: string
    onChange?: (v: string | undefined) => void
  }) => (
    <textarea
      data-testid="monaco-mock"
      defaultValue={defaultValue ?? ''}
      onChange={(e) => onChange?.(e.target.value)}
    />
  ),
}))

/**
 * StrategyPage 组件测(照 brief §完成标准 3 用例)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/strategy.ts 提供 detail/codes/codeDetail/...)。
 * 用 MemoryRouter(StrategyPage 用 useNavigate 跳 /backtest)。
 */
async function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <StrategyPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...utils, user, qc }
}

describe('StrategyPage', () => {
  it('渲染 header + list rail 5 策略,默认选中 BTC Trend Rider', async () => {
    await renderPage()
    // MSW 返回 5 策略,默认选中第一个 BTC Trend Rider(h1 + list rail 卡都出现 → findAllByText)
    await waitFor(() => {
      expect(screen.getAllByText('BTC Trend Rider').length).toBeGreaterThanOrEqual(1)
    })
    // list rail 5 策略 + 新建按钮
    expect(screen.getByText('ETH Mean Reversion')).toBeInTheDocument()
    expect(screen.getByText('SOL 做市')).toBeInTheDocument()
    expect(screen.getByText('Grid Scalper')).toBeInTheDocument()
    expect(screen.getByText('Funding Arb')).toBeInTheDocument()
    expect(screen.getByText('新建策略')).toBeInTheDocument()
    // Header 按钮
    expect(screen.getByText('跑回测')).toBeInTheDocument()
    expect(screen.getByText('发布版本')).toBeInTheDocument()
  })

  it('发布版本 modal 打开-关闭', async () => {
    const { user } = await renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('BTC Trend Rider').length).toBeGreaterThanOrEqual(1)
    })
    await user.click(screen.getByRole('button', { name: /发布版本/ }))
    expect(await screen.findByText('发布代码版本')).toBeInTheDocument()
    expect(screen.getByText('版本号')).toBeInTheDocument()
    expect(screen.getByText('变更说明')).toBeInTheDocument()
    // 取消关闭
    await user.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => {
      expect(screen.queryByText('发布代码版本')).not.toBeInTheDocument()
    })
  })

  it('版本 modal 打开见 3 态版本列表', async () => {
    const { user } = await renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('BTC Trend Rider').length).toBeGreaterThanOrEqual(1)
    })
    // code editor 卡的"版本"按钮(用 title 定位,避"发布版本"按钮也匹配 /版本/)
    const versionBtn = screen.getByTitle('查看代码版本时间线')
    await user.click(versionBtn)
    expect(await screen.findByText('代码版本')).toBeInTheDocument()
    // 策略 1 有 3 个版本(v3 DRAFT / v2 PUBLISHED / v1 ARCHIVED)
    expect(screen.getByText('加入 ADX 过滤 · 放宽止损')).toBeInTheDocument()
    // Chip 标签
    expect(screen.getByText('DRAFT')).toBeInTheDocument()
    expect(screen.getByText('PUBLISHED')).toBeInTheDocument()
    expect(screen.getByText('ARCHIVED')).toBeInTheDocument()
  })
})
