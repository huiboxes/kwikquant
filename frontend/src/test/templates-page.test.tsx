import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route, useSearchParams } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { TemplatesPage } from '@/pages/TemplatesPage'
import { Toaster } from '@/components/Toast'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

// Monaco 在 jsdom 不可用(canvas/WebWorker),mock 成 textarea(模板详情 dialog 预览用)。
vi.mock('@/lib/monaco', () => ({}))
vi.mock('@monaco-editor/react', () => ({
  default: ({ value }: { value?: string }) => (
    <textarea data-testid="monaco-mock" readOnly value={value ?? ''} onChange={() => {}} />
  ),
}))

/** fork 成功跳转目标：渲染策略 id + query 供断言。 */
function StrategyMarker() {
  const [params] = useSearchParams()
  return <div>strategy-page:{params.get('strategyId')}</div>
}

async function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/templates']}>
        <Routes>
          <Route path="/templates" element={<TemplatesPage />} />
          <Route path="/strategy" element={<StrategyMarker />} />
        </Routes>
      </MemoryRouter>
      {/* 生产 Toaster 挂 main.tsx；测试树自挂一份，toast 文案才可断言 */}
      <Toaster />
    </QueryClientProvider>,
  )
  return { ...utils, user }
}

describe('TemplatesPage 策略模板库(P1-3)', () => {
  it('渲染官方模板卡片 + 标签过滤 chips', async () => {
    await renderPage()
    expect(await screen.findByText('均线双金叉')).toBeInTheDocument()
    expect(screen.getByText('固定网格')).toBeInTheDocument()
    expect(screen.getByText('RSI 超卖反转')).toBeInTheDocument()
    // 标签过滤(去重保序):全部/趋势跟踪/网格/均值回归
    expect(screen.getByRole('button', { name: '全部' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '趋势跟踪' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '网格' })).toBeInTheDocument()
    // 推荐配置展示(mono 行)
    expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThanOrEqual(2)
    expect(screen.getAllByText(/推荐回测/).length).toBe(3)
  })

  it('点标签过滤 → 只显示匹配模板；再点取消过滤', async () => {
    const { user } = await renderPage()
    await screen.findByText('均线双金叉')
    await user.click(screen.getByRole('button', { name: '均值回归' }))
    // fixed-grid + rsi-reversal 带"均值回归"标签；ma-double-cross 被过滤
    expect(screen.queryByText('均线双金叉')).not.toBeInTheDocument()
    expect(screen.getByText('固定网格')).toBeInTheDocument()
    expect(screen.getByText('RSI 超卖反转')).toBeInTheDocument()
    // 再点一次同标签 → 恢复全部
    await user.click(screen.getByRole('button', { name: '均值回归' }))
    expect(await screen.findByText('均线双金叉')).toBeInTheDocument()
  })

  it('卡片 fork → 成功跳策略工作台深链(strategyId=900)', async () => {
    const { user } = await renderPage()
    await screen.findByText('均线双金叉')
    const forkButtons = screen.getAllByRole('button', { name: /fork 使用/ })
    await user.click(forkButtons[0])
    // 跳转 /strategy?strategyId=900(fixture fork 返回 id=900)
    expect(await screen.findByText('strategy-page:900')).toBeInTheDocument()
  })

  it('fork 首回测降级(skipReason)→ 仍跳转，提示手动回测', async () => {
    server.use(
      http.post('/api/v1/strategies/templates/:key/fork', () =>
        HttpResponse.json(
          envelope({
            strategy: {
              id: 900,
              name: '均线双金叉',
              description: '',
              symbol: 'BTC/USDT',
              exchange: 'BINANCE',
              marketType: 'SPOT',
              marginMode: null,
              leverage: null,
              intervalValue: '1h',
              status: 'DRAFT',
              parameters: '{}',
              createdAt: '2026-08-20T00:00:00Z',
              updatedAt: '2026-08-20T00:00:00Z',
              version: null,
              pnl: null,
              exchangeAccountId: null,
              stopReason: null,
            },
            firstBacktestTaskId: null,
            backtestSkipReason: '回测并发配额已满，请稍后在策略工作台手动提交首次回测',
          }),
        ),
      ),
    )
    const { user } = await renderPage()
    await screen.findByText('均线双金叉')
    await user.click(screen.getAllByRole('button', { name: /fork 使用/ })[0])
    // fork 本身成功：仍跳策略工作台
    expect(await screen.findByText('strategy-page:900')).toBeInTheDocument()
    // skipReason 进 toast(sonner 渲染到 body)
    expect(screen.getByText(/回测并发配额已满/)).toBeInTheDocument()
  })

  it('查看详情 → dialog 展示源码预览，可 fork', async () => {
    const { user } = await renderPage()
    await screen.findByText('均线双金叉')
    await user.click(screen.getAllByRole('button', { name: /查看详情/ })[0])
    // Monaco mock 渲染模板源码(详情加载完成标志)
    const editor = (await screen.findByTestId('monaco-mock')) as HTMLTextAreaElement
    await waitFor(() => expect(editor.value).toContain('def on_bar'))
    // dialog 内 fork 按钮可用
    const dialogFork = screen
      .getAllByRole('button', { name: /fork 使用/ })
      .find((b) => b.closest('[role=dialog]'))
    expect(dialogFork).toBeTruthy()
    await user.click(dialogFork!)
    expect(await screen.findByText('strategy-page:900')).toBeInTheDocument()
  })

  it('fork 失败(500)→ 错误 toast，留在模板页', async () => {
    server.use(
      http.post('/api/v1/strategies/templates/:key/fork', () =>
        HttpResponse.json(envelope(null, 5001, '内部错误'), { status: 500 }),
      ),
    )
    const { user } = await renderPage()
    await screen.findByText('均线双金叉')
    await user.click(screen.getAllByRole('button', { name: /fork 使用/ })[0])
    // toast 标题 + description 透出后端原因(此处 MSW override 返 5001 '内部错误')
    expect(await screen.findByText('fork 失败')).toBeInTheDocument()
    expect(await screen.findByText('内部错误')).toBeInTheDocument()
    // 仍在模板页(未跳转)
    expect(screen.queryByText(/^strategy-page:/)).not.toBeInTheDocument()
  })

  it('列表加载失败 → ErrorState 带重试', async () => {
    server.use(
      http.get('/api/v1/strategies/templates', () =>
        HttpResponse.json(envelope(null, 5001, '内部错误'), { status: 500 }),
      ),
    )
    await renderPage()
    // 脱敏通用文案(不裸透后端 '内部错误'/英文信封),带重试按钮
    expect(await screen.findByText(/暂时无法加载模板库/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重试/ })).toBeInTheDocument()
  })
})
