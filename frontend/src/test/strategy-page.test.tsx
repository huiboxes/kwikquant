import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { StrategyPage } from '@/pages/StrategyPage'
import { CreateStrategyDialog } from '@/pages/strategy/CreateStrategyDialog'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

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
    // BottomControlBar 控件(回测按钮;右侧 RightPanel 也有"回测"tab,故用 getAll)
    expect(screen.getAllByText('回测').length).toBeGreaterThanOrEqual(1)
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
    expect(screen.getByText('变更说明')).toBeInTheDocument()
    // 版本号融进 Description:定稿当前代码为版本 v3(strategy 1 DRAFT versionNumber=3)
    expect(screen.getByText(/^v3$/)).toBeInTheDocument()
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
    expect(screen.getAllByText('草稿').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('已发布').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('已归档').length).toBeGreaterThanOrEqual(1)
  })

  it('CreateStrategyDialog:传 symbol/marketType prop → 提交 req 用 prop 非默认 BTC/USDT', async () => {
    const onCreate = vi.fn()
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}>
        <CreateStrategyDialog
          open={true}
          creating={false}
          onCreate={onCreate}
          onOpenChange={() => {}}
          symbol="ETH/USDT"
          marketType="PERP"
        />
      </QueryClientProvider>,
    )
    const nameInput = screen.getByPlaceholderText('BTC 均线交叉')
    await userEvent.type(nameInput, '我的策略')
    await userEvent.click(screen.getByRole('button', { name: /创建策略/ }))
    await waitFor(() => {
      expect(onCreate.mock.calls[0][0]).toMatchObject({
        symbol: 'ETH/USDT',
        marketType: 'PERP',
      })
    })
  })

  it('?symbol=ETH/USDT 跳转 → 自动开"创建策略" dialog(预填 symbol)', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/strategy?symbol=ETH/USDT']}>
          <StrategyPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    // dialog 自动 open(showCreate 初始 = !!querySymbol;标题 + 按钮都"创建策略"用 findAll)
    expect((await screen.findAllByText('创建策略')).length).toBeGreaterThan(0)
  })

  it('?strategyId=2 跳转 → 自动选中策略 2 ETH Mean Reversion(非默认 BTC Trend Rider)', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/strategy?strategyId=2']}>
          <StrategyPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    // 等 strategies 加载 + ref guard setSelectedId(2) + detail 加载
    await waitFor(() => {
      expect(screen.getAllByText(/ETH Mean Reversion/).length).toBeGreaterThanOrEqual(1)
    })
    // 选中策略 2,trigger 不显默认策略 1 BTC Trend Rider
    expect(screen.queryByText(/BTC Trend Rider/)).not.toBeInTheDocument()
  })

  /**
   * 回测未发布预检(问题 1):策略无 PUBLISHED 版本时点回测 → 弹 ConfirmDialog
   * "未发布版本,是否先发布后回测?" 而非直接提交(后端会返 7006)。
   * 原 bug:BottomControlBar.handleBacktest 不预检 published,直接提交。
   */
  it('点回测时策略无 PUBLISHED 版本 → 弹"是否先发布后回测?"非直接提交', async () => {
    // override 策略 1 的 codes:只有 DRAFT,无 PUBLISHED
    server.use(
      http.get('/api/v1/strategies/1/codes', () =>
        HttpResponse.json(
          envelope([
            {
              id: 11,
              strategyId: 1,
              versionNumber: 3,
              status: 'DRAFT',
              language: 'python',
              changelog: '草稿未发布',
              createdAt: '2026-07-12T14:00:00Z',
              updatedAt: '2026-07-12T14:00:00Z',
            },
          ]),
        ),
      ),
    )
    const { user } = await renderPage()
    await waitFor(() => {
      expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThanOrEqual(1)
    })
    // 等 codes 加载完(meta line "版本 (N)" N>0 出现 = useStrategyCodes 返回),
    // 避免 codes=undefined 时 hasPublished 误判 false 巧合弹 prompt(预检需 codes 就绪)。
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /版本 \([1-9]/ })).toBeInTheDocument()
    })
    // 点 BottomControlBar 回测按钮(data-testid 区分 RightPanel 同名"回测"tab)
    await user.click(screen.getByTestId('backtest-run-btn'))
    // 期望弹 ConfirmDialog 标题,而非直接提交(无"回测已提交"toast)
    expect(await screen.findByText('未发布版本,是否先发布后回测?')).toBeInTheDocument()
  })

  it('点回测时策略有 PUBLISHED 版本 → 不弹 prompt,直接提交回测', async () => {
    // 策略 1 默认 codes 有 v2 PUBLISHED(handlers/strategy.ts),无需 override
    const { user } = await renderPage()
    await waitFor(() => {
      expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThanOrEqual(1)
    })
    // 等 codes 加载完(策略 1 默认 3 版本 → "版本 (3)"),避免 codes=undefined 预检误判。
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /版本 \([1-9]/ })).toBeInTheDocument()
    })
    await user.click(screen.getByTestId('backtest-run-btn'))
    // 已发布 → 预检通过,不弹 prompt(核心修复目标:有 PUBLISHED 不弹"是否先发布")
    // 等 prompt 可能弹的窗口(异步 setState + ConfirmDialog 渲染 ~300ms),确认不弹
    await new Promise((r) => setTimeout(r, 600))
    expect(screen.queryByText('未发布版本,是否先发布后回测?')).not.toBeInTheDocument()
  })

  it('STOPPED 策略渲染「重新启动」按钮(非死胡同 toast)', async () => {
    // MSW override:list + detail 都返单个 STOPPED 策略(避免 list/detail status 不一致)
    const stopped = {
      id: 1,
      name: 'BTC Rider',
      description: '',
      symbol: 'BTC/USDT',
      exchange: 'BINANCE',
      marketType: 'SPOT',
      intervalValue: '15m',
      status: 'STOPPED',
      parameters: '{}',
      createdAt: '2026-07-01T08:00:00Z',
      updatedAt: '2026-07-09T12:00:00Z',
      version: 'v1.0.0',
      pnl: 0,
      exchangeAccountId: 1,
    }
    server.use(
      http.get('/api/v1/strategies', () => HttpResponse.json(envelope([stopped]))),
      http.get('/api/v1/strategies/1', () => HttpResponse.json(envelope(stopped))),
    )
    await renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /重新启动/ })).toBeInTheDocument()
    })
    // STOPPED 状态徽章「已停止」(StrategyStatusBadge)仍显示(它是状态标识非操作按钮),
    // 死胡同 toast 按钮已删——「重新启动」可操作按钮存在即验证不再死胡同
  })

  it('STOPPED:点重新启动→StartDialog→点重新启动→调 POST /restart(非 /start)', async () => {
    const stopped = {
      id: 1,
      name: 'BTC Rider',
      description: '',
      symbol: 'BTC/USDT',
      exchange: 'BINANCE',
      marketType: 'SPOT',
      intervalValue: '15m',
      status: 'STOPPED',
      parameters: '{}',
      createdAt: '2026-07-01T08:00:00Z',
      updatedAt: '2026-07-09T12:00:00Z',
      version: 'v1.0.0',
      pnl: 0,
      exchangeAccountId: 1,
    }
    let restartCalled = false
    let startCalled = false
    server.use(
      http.get('/api/v1/strategies', () => HttpResponse.json(envelope([stopped]))),
      http.get('/api/v1/strategies/1', () => HttpResponse.json(envelope(stopped))),
      http.post('/api/v1/strategies/1/restart', () => {
        restartCalled = true
        return HttpResponse.json(envelope({ ...stopped, status: 'RUNNING' }))
      }),
      http.post('/api/v1/strategies/1/start', () => {
        startCalled = true
        return HttpResponse.json(envelope({ ...stopped, status: 'RUNNING' }))
      }),
    )
    const { user } = await renderPage()
    // StrategySelector「重新启动」按钮(第一个)
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: /重新启动/ }).length).toBeGreaterThan(0),
    )
    await user.click(screen.getAllByRole('button', { name: /重新启动/ })[0])
    // StartDialog 打开(标题「重新启动策略」)
    expect(await screen.findByText('重新启动策略')).toBeInTheDocument()
    // 点 dialog 内「重新启动」(footer)——dialog portal 后渲染,取最后一个
    const btns = await screen.findAllByRole('button', { name: /重新启动/ })
    await user.click(btns[btns.length - 1])
    await waitFor(() => expect(restartCalled).toBe(true))
    expect(startCalled).toBe(false)
  })

  it('FsmDialog:显示「↻ 重新启动」回环 + 「已停止→运行中」规则,无「终态」', async () => {
    const { user } = await renderPage()
    await waitFor(() => {
      expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThanOrEqual(1)
    })
    // FsmDialog 触发:状态 badge 按钮(title="查看状态流转规则")
    await user.click(screen.getByTitle('查看状态流转规则'))
    expect(await screen.findByText(/↻ 重新启动/)).toBeInTheDocument()
    expect(screen.getByText(/已停止 → 运行中/)).toBeInTheDocument()
    // 「终态」措辞已去(STOPPED 不再是真终态,可重新启动)
    expect(screen.queryByText(/终态/)).not.toBeInTheDocument()
  })

  it('自动保存倒计时:改代码后状态栏显示"未保存 Ns"', async () => {
    await renderPage()
    // 等 codeDetail 加载完(显示"已保存" = draftCodeId 就绪 + 可编辑,非"模板预览")
    await waitFor(() => expect(screen.getByText('已保存')).toBeInTheDocument())
    const ta = screen.getByTestId('monaco-mock')
    fireEvent.change(ta, { target: { value: 'print(1)' } })
    // dirty → countdown 3s 立即显示
    expect(await screen.findByText(/未保存 \d+s/)).toBeInTheDocument()
  })

  it('Cmd+S 阻止浏览器保存网页默认 + 立即保存(跳过 3s debounce)', async () => {
    await renderPage()
    await waitFor(() => expect(screen.getByText('已保存')).toBeInTheDocument())
    const ta = screen.getByTestId('monaco-mock')
    fireEvent.change(ta, { target: { value: 'print(2)' } })
    // 等 dirty 渲染 + saveStatusRef 同步(Cmd+S listener [] 依赖读 ref,防 stale)
    await waitFor(() => expect(screen.getByText(/未保存/)).toBeInTheDocument())
    // 立即 Cmd+S(自动保存 timer 仍 pending,未到 3s)
    const ev = new KeyboardEvent('keydown', { key: 's', metaKey: true, bubbles: true })
    const spy = vi.spyOn(ev, 'preventDefault')
    document.dispatchEvent(ev)
    expect(spy).toHaveBeenCalled()
    // doSave 触发 → 保存中(不等 3s 自动保存)
    await waitFor(() => expect(screen.getByText('保存中…')).toBeInTheDocument())
  })
})
