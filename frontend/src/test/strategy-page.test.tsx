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
 * StrategyPage 组件测(IDE 工作台布局)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/strategy.ts 提供 detail/codes/codeDetail/...)。
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
  it('渲染 IDE 布局:策略选择器 + 编辑器 + BottomControlBar,默认选中第一个策略', async () => {
    await renderPage()
    // MSW 返回 5 策略,默认选中第一个 BTC Trend Rider(StrategySelector 下拉 + BottomControlBar 出现)
    await waitFor(() => {
      expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThanOrEqual(1)
    })
    // BottomControlBar 控件
    expect(screen.getByText('回测')).toBeInTheDocument()
    // 发布版本按钮(StrategySelector 右侧)
    expect(screen.getByText('发布版本')).toBeInTheDocument()
    // Monaco 编辑器 mock
    expect(screen.getByTestId('monaco-mock')).toBeInTheDocument()
  })

  it('发布版本 modal 打开-关闭', async () => {
    const { user } = await renderPage()
    await waitFor(() => {
      expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThanOrEqual(1)
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
      expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThanOrEqual(1)
    })
    // meta line 的版本按钮(文本 "版本 (N)")
    const versionBtn = screen.getByRole('button', { name: /版本 \(/ })
    await user.click(versionBtn)
    expect(await screen.findByText('代码版本')).toBeInTheDocument()
    // 策略 1 有 3 个版本(v3 DRAFT / v2 PUBLISHED / v1 ARCHIVED)
    expect(screen.getByText('加入 ADX 过滤 · 放宽止损')).toBeInTheDocument()
    // Chip 标签(modal VersionRow + meta line 都可能有,用 getAllByText)
    expect(screen.getAllByText('DRAFT').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('PUBLISHED').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('ARCHIVED').length).toBeGreaterThanOrEqual(1)
  })
})
