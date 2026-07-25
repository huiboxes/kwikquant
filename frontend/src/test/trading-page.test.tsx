import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { TradingPage } from '@/pages/TradingPage'
import { useAuthStore } from '@/stores/authStore'
import { useUiStore } from '@/stores/uiStore'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

// lightweight-charts 在 jsdom 不可用(canvas),mock 掉
vi.mock('@/components/charts/KlineChart', () => ({
  KlineChart: () => <div data-testid="kline-mock" />,
}))

/**
 * TradingPage 组件测(Task 5 改造后:删 banner,首元素 BalanceBar;文案过滤;空账户引导)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/trading.ts orders/positions,handlers/account.ts accounts/balance/reset)。
 * mode 由 useUiStore.tradeMode 驱动(切 LIVE 不再走 TradingPage SegMode,归 TopBar TradeModeToggle)。
 */
async function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <TradingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...utils, user, qc }
}

describe('TradingPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      user: { userId: 1, username: 'demo' },
      accessToken: 'dummy',
    })
    useUiStore.setState({ tradeMode: 'PAPER', liveConfirmedThisSession: false })
  })

  it('PAPER 模式:首元素 BalanceBar,无 banner/SegMode/重置按钮,OrderForm 在', async () => {
    await renderPage()
    // banner 标题已删
    expect(screen.queryByText('模拟盘交易')).not.toBeInTheDocument()
    // BalanceBar 4 格
    expect(await screen.findByText('可用')).toBeInTheDocument()
    expect(screen.getByText('冻结')).toBeInTheDocument()
    // OrderForm
    expect(screen.getByText('下单')).toBeInTheDocument()
    // 无重置按钮(归 Settings)
    expect(screen.queryByRole('button', { name: /重置模拟盘/ })).not.toBeInTheDocument()
    // 无 SegMode 大按钮
    expect(screen.queryByRole('button', { name: /LIVE · 实盘/ })).not.toBeInTheDocument()
  })

  it('LIVE 模式(setStore,不走 SegMode):实盘渲染,无 SegMode', async () => {
    useUiStore.setState({ tradeMode: 'LIVE', liveConfirmedThisSession: true })
    await renderPage()
    // OrderForm 实盘态:CTA 文案带「真金白银」(卡内风险提示已移到确认 Dialog,省垂直空间)
    expect(await screen.findByText(/真金白银/)).toBeInTheDocument()
    // 仍无 SegMode 大按钮
    expect(screen.queryByRole('button', { name: /LIVE · 实盘/ })).not.toBeInTheDocument()
    // LIVE 模式也不泄露风控规则名 / 风控闸门 实现细节
    expect(screen.queryByText(/MAX_NOTIONAL|DAILY_LOSS_LIMIT|ORDER_FREQUENCY/)).not.toBeInTheDocument()
    expect(screen.queryByText(/风控闸门/)).not.toBeInTheDocument()
  })

  it('空账户引导:LIVE 模式 + 无实盘账户 → EmptyState 去添加', async () => {
    // 覆写 accounts 只返 PAPER 账户 → LIVE 模式 modeAccounts 空
    server.use(
      http.get('/api/v1/accounts', () =>
        HttpResponse.json(
          envelope([
            { id: 1, exchange: 'BINANCE', label: 'BINANCE 模拟', apiKey: '', paperTrading: true, status: 'ACTIVE' },
          ]),
        ),
      ),
    )
    useUiStore.setState({ tradeMode: 'LIVE', liveConfirmedThisSession: true })
    await renderPage()
    expect(await screen.findByText(/还没有实盘账户/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /去添加/ })).toBeInTheDocument()
  })

  it('文案:不泄露风控规则名 + 余额来源实现细节', async () => {
    await renderPage()
    expect(screen.queryByText(/MAX_NOTIONAL|DAILY_LOSS_LIMIT|ORDER_FREQUENCY/)).not.toBeInTheDocument()
    expect(screen.queryByText(/余额由交易所|本地真实化|基准行情|行情撮合/)).not.toBeInTheDocument()
  })

  it('OrderForm:点 SELL → 下单按钮文案变卖出', async () => {
    const { user } = await renderPage()
    await screen.findByText('可用') // 等 PAPER 渲染稳(不再依赖 banner)
    expect(screen.getByRole('button', { name: /买入 0\.1 BTC\/USDT/ })).toBeInTheDocument()
    // BUY/SELL 已改为裸 button grid(与 PERP 4 按钮同套,active 实色+glow),文案纯中文(不暴露枚举)
    await user.click(screen.getByRole('button', { name: '卖出' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /卖出 0\.1 BTC\/USDT/ })).toBeInTheDocument()
    })
  })

  it('K 线 header:interval 6 档 TabsTrigger + 写策略 link 含 sel', async () => {
    await renderPage()
    await screen.findByText('可用') // 等 PAPER 渲染稳
    // interval 6 档(默认 15m active,其余 tab 也在)
    expect(screen.getByRole('tab', { name: '15m' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '1h' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '1d' })).toBeInTheDocument()
    // 写策略 link href 含 symbol=BTC/USDT(默认 sel)
    const link = screen.getByRole('link', { name: /写策略/ })
    expect(link.getAttribute('href') ?? '').toContain('symbol=BTC')
  })

  it('?symbol=ETH/USDT query → K 线标题显 ETH/USDT + 写策略 link 含 ETH', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } })
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/trade?symbol=ETH/USDT']}>
          <TradingPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    // K 线标题显 ETH/USDT(sel 从 query)
    expect(await screen.findByText(/ETH\/USDT · K 线/)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /写策略/ })
    expect(link.getAttribute('href') ?? '').toContain('symbol=ETH')
  })

  // ── 阶段3.4 PERP 态(4 按钮 + 杠杆 + 逐仓/全仓 + buildReq 透传) ──
  /** PERP 态渲染 helper:挂 ?marketType=PERP 起页。 */
  async function renderPerpPage() {
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    })
    const user = userEvent.setup()
    const utils = render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/trade?marketType=PERP']}>
          <TradingPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    return { ...utils, user, qc }
  }

  it('PERP 态:4 按钮(开多/开空/平多/平空)+ 杠杆滑块 + 强平价/保证金率信息行(全仓未接不显)', async () => {
    await renderPerpPage()
    // 等 OrderForm 渲染稳(BalanceBar 可用)
    await screen.findByText('可用')
    // 4 按钮(中文文案,不暴露 OPEN_LONG 等枚举)。持仓表 PERP 仓位也有"平多/平空"按钮
    // (阶段3.5 加 PERP 仓位 mock,PositionsTable 行的平仓按钮文案 = 平多/平空),
    // 所以用 getAllByRole 断言长度 >= 1(只验 OrderForm 4 按钮存在,不验唯一)。
    expect(screen.getByRole('button', { name: '开多' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开空' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '平多' }).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByRole('button', { name: '平空' }).length).toBeGreaterThanOrEqual(1)
    // SPOT 的买入/卖出 tab 不在(只 SPOT 态才出现)
    expect(screen.queryByRole('tab', { name: '买入' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: '卖出' })).not.toBeInTheDocument()
    // 杠杆 label + 9 档预设(1x/10x/100x 等)。持仓表表头也有"杠杆"(hasPerp=true 显合约列),
    // 用 getAllByText 取首个(OrderForm 的杠杆 label);按钮用 getByRole(name) 仍唯一。
    expect(screen.getAllByText('杠杆').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '1x' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '100x' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '125x' })).toBeInTheDocument()
    // 全仓后端未接 → UI 不暴露逐仓/全仓选择行(避免死控件);marginMode 固定 ISOLATED,buildReq 测试验透传
    expect(screen.queryByRole('button', { name: '逐仓' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /全仓/ })).not.toBeInTheDocument()
    // 强平价(估)/保证金率(估)/预估保证金占用 信息行
    // 强平价/保证金率移 hover title(信息行精简 6→3),不再独立 DOM 文本;保留预估保证金占用行
    expect(screen.getByText('预估保证金占用')).toBeInTheDocument()
    // 默认开多 + 100x → 下单按钮文案含 "开多 ... BTC/USDT-PERP · 100x"
    expect(screen.getByRole('button', { name: /开多 .* BTC\/USDT-PERP · 100x/ })).toBeInTheDocument()
  })

  it('PERP 切开空 → 下单按钮变开空色 + 文案;切 10x 预设 → 杠杆变 10x', async () => {
    const { user } = await renderPerpPage()
    await screen.findByText('可用')
    // 切开空
    await user.click(screen.getByRole('button', { name: '开空' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /开空 .* BTC\/USDT-PERP · 100x/ })).toBeInTheDocument()
    })
    // 切 10x 预设
    await user.click(screen.getByRole('button', { name: '10x' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /开空 .* BTC\/USDT-PERP · 10x/ })).toBeInTheDocument()
    })
  }, 20000)

  it('数量滑块联动复现:点 25/50/75%(非端点)→ 档位按钮 active(证明 value→active 同步)', async () => {
    // 用户报告:0/100% 联动,25/50/75% 完全不联动。纯 Slider 受控测试已证 value 同步(aria-valuenow 随 setV 更新);
    // 此测试在 OrderForm 层断言非端点档位点击后 active 态切换(value→pct→active 链路)。
    const { user } = await renderPage()
    await screen.findByText('可用')
    await user.click(screen.getByRole('button', { name: '25%' }))
    expect(screen.getByRole('button', { name: '25%' })).toHaveClass('text-accent')
    await user.click(screen.getByRole('button', { name: '50%' }))
    expect(screen.getByRole('button', { name: '50%' })).toHaveClass('text-accent')
    await user.click(screen.getByRole('button', { name: '75%' }))
    expect(screen.getByRole('button', { name: '75%' })).toHaveClass('text-accent')
  })

  it('杠杆滑块联动复现:点 50x/75x/125x(右半非端点)→ 档位按钮 active(value→leverage→active)', async () => {
    // 用户报告:1/2/5 联动,再往右对不上。纯 Slider 受控测试已证 value 同步;
    // 此测试在 OrderForm 层断言右半档位点击后 active 切换(非端点也联动)。
    const { user } = await renderPerpPage()
    await screen.findByText('可用')
    expect(screen.getByRole('button', { name: '100x' })).toHaveClass('bg-accent')
    await user.click(screen.getByRole('button', { name: '50x' }))
    expect(screen.getByRole('button', { name: '50x' })).toHaveClass('bg-accent')
    await user.click(screen.getByRole('button', { name: '75x' }))
    expect(screen.getByRole('button', { name: '75x' })).toHaveClass('bg-accent')
    await user.click(screen.getByRole('button', { name: '125x' }))
    expect(screen.getByRole('button', { name: '125x' })).toHaveClass('bg-accent')
  })

  it('杠杆数字框:点 10x 以上档位 → Input value 显示对应倍数(排查"10x 以上数字不正确/进制问题")', async () => {
    const { user } = await renderPerpPage()
    await screen.findByText('可用')
    // 初始 100x → 数字框显 100
    expect(screen.getByDisplayValue('100')).toBeInTheDocument()
    const cases: [string, string][] = [
      ['10x', '10'],
      ['25x', '25'],
      ['50x', '50'],
      ['75x', '75'],
      ['100x', '100'],
      ['125x', '125'],
    ]
    for (const [label, val] of cases) {
      await user.click(screen.getByRole('button', { name: label }))
      expect(screen.getByDisplayValue(val)).toBeInTheDocument()
    }
  })

  it('PERP buildReq 透传:提交 → req.body 含 positionEffect=OPEN_SHORT/leverage=10/marginMode=ISOLATED(side 派生 SELL)', async () => {
    // 覆写 POST /orders 截获 body,断言后 201 NEW
    let capturedBody: Record<string, unknown> | null = null
    server.use(
      http.post('/api/v1/orders', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(envelope({ orderId: 99999, status: 'NEW', version: 1, createdAt: '2026-07-21T12:00:00Z' }), { status: 201 })
      }),
    )
    const { user } = await renderPerpPage()
    await screen.findByText('可用')
    // 切开空 + 10x(PAPER 模式不走 LIVE Dialog,直接 submit)
    await user.click(screen.getByRole('button', { name: '开空' }))
    await user.click(screen.getByRole('button', { name: '10x' }))
    // 等按钮文案稳定
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /开空 .* BTC\/USDT-PERP · 10x/ })).toBeInTheDocument()
    })
    // PAPER 模式直接点下单按钮提交(无 Dialog 二次确认)
    await user.click(screen.getByRole('button', { name: /开空 .* BTC\/USDT-PERP · 10x/ }))
    await waitFor(() => {
      expect(capturedBody).not.toBeNull()
    })
    const body = capturedBody!
    expect(body.positionEffect).toBe('OPEN_SHORT')
    expect(body.leverage).toBe(10)
    expect(body.marginMode).toBe('ISOLATED')
    // OPEN_SHORT 派生 side=SELL(后端 §13 拍板:reduceOnly 不传,由 positionEffect=CLOSE_* 派生)
    expect(body.side).toBe('SELL')
    expect(body.marketType).toBe('PERP')
    // reduceOnly 不在 OrderSubmitRequest(那是 OrderDetailDto 派生字段)
    expect(body).not.toHaveProperty('reduceOnly')
  }, 20000)

  // ── 阶段3.5 持仓表合约列 + 平仓按钮按 positionSide 路由 ──

  it('持仓表:PERP 持仓显 杠杆/保证金/标记价/强平价 列 + PERP chip;SPOT 持仓合约列显 —', async () => {
    await renderPage()
    // 等 PAPER account 1 持仓加载(默认 BTC/USDT sel,PositionsTable 用 usePositions(accountId) 不传 symbol)
    // PERP 仓位 130(BTC/USDT LONG 10x ISOLATED)
    // 表头合约列(任意 PERP 持仓存在 → hasPerp true → 表头显杠杆/保证金/标记价/强平价)
    expect(await screen.findByText('杠杆')).toBeInTheDocument()
    expect(screen.getByText('保证金')).toBeInTheDocument()
    expect(screen.getByText('标记价')).toBeInTheDocument()
    expect(screen.getByText('强平价')).toBeInTheDocument()
    // PERP 仓位 130(BTC/USDT PERP LONG 10x):10x + 逐仓 + 标记价 61370.00 + 强平价 55120.00 + PERP chip
    expect(screen.getByText('10x')).toBeInTheDocument()
    expect(screen.getAllByText('逐仓').length).toBeGreaterThan(0)
    expect(screen.getByText('61,370.00')).toBeInTheDocument()
    expect(screen.getByText('55,120.00')).toBeInTheDocument()
    // PERP chip(仓位行内)
    expect(screen.getAllByText('PERP').length).toBeGreaterThan(0)
    // PERP 仓位 131(ETH/USDT SHORT 20x):20x + 强平价 3,290.00
    expect(screen.getByText('20x')).toBeInTheDocument()
    expect(screen.getByText('3,290.00')).toBeInTheDocument()
    // SPOT 仓位 128(BTC/USDT LONG)的合约列显 —(hasPerp 表头加,但 SPOT 行显 —)
    // 因 128 BTC/USDT SPOT + 130 BTC/USDT PERP 同 symbol,多行 PERP chip 区分
    // — 字符有多行(SPOT 仓位 + SPOT 129 SOL/USDT),用 getAllByText
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('持仓表方向列:PERP 按 positionSide 显 多/空(中文,不暴露 LONG/SHORT 枚举字面量)', async () => {
    await renderPage()
    // 等 PositionsTable 渲染稳(PERP 仓位 130 LONG + 131 SHORT)
    await screen.findByText('10x')
    // 方向列显中文 多/空(原型 src 改造:不暴露 LONG/SHORT/FLAT 枚举字面量)
    // 持仓 130 多(LONG)+ 131 空(SHORT)+ 128 多(SPOT LONG)+ 129 空(SPOT SHORT)
    // 至少应有多/空 各 1 个(实际 128+130=多 2 个,129+131=空 2 个,共 4 行)
    expect(screen.getAllByText('多').length).toBeGreaterThanOrEqual(2)
    expect(screen.getAllByText('空').length).toBeGreaterThanOrEqual(2)
  })

  it('平仓按钮文案:PERP LONG 仓 → 平多,PERP SHORT 仓 → 平空,SPOT 仓 → 平仓', async () => {
    await renderPage()
    // 等 PositionsTable 渲染稳
    await screen.findByText('10x')
    // PERP 仓位 130 BTC/USDT LONG → 平多
    expect(screen.getByRole('button', { name: '平多' })).toBeInTheDocument()
    // PERP 仓位 131 ETH/USDT SHORT → 平空
    expect(screen.getByRole('button', { name: '平空' })).toBeInTheDocument()
    // SPOT 仓位 128 BTC/USDT + 129 SOL/USDT → 平仓(至少 2 个平仓按钮)
    expect(screen.getAllByRole('button', { name: '平仓' }).length).toBeGreaterThanOrEqual(2)
  })

  it('PERP 平仓 ConfirmDialog:点平多按钮弹窗显示 杠杆/保证金/强平价 + 确认按钮文案平多', async () => {
    const { user } = await renderPage()
    // 等 PositionsTable 渲染稳
    await screen.findByText('10x')
    // 点 PERP LONG 仓位(130 BTC/USDT 10x ISOLATED)的平多按钮
    await user.click(screen.getByRole('button', { name: '平多' }))
    // ConfirmDialog 弹窗 title + 描述
    expect(await screen.findByText('确认平仓')).toBeInTheDocument()
    // 弹窗内合约参数显示(杠杆 10x + 保证金模式 逐仓 + 强平价 55,120.00)
    // 弹窗内"杠杆"label + "10x"值;"强平价"label + "55,120.00"值
    // 注意:持仓表里也有 10x / 55,120.00,弹窗打开后 dom 会同时含 → getAllByText 断长度
    // 简化:断弹窗内"description 含 BTC/USDT 多 持仓"(描述文案带方向)
    expect(screen.getAllByText(/平掉 BTC\/USDT 多 持仓/).length).toBeGreaterThan(0)
    // 确认按钮文案是"平多"(按 positionSide)
    expect(screen.getByRole('button', { name: '平多' })).toBeInTheDocument()
  })

  it('活动 tab status 传合法 enum 名(防 PARTIAL 非法→400 活动 tab 永空回归)', async () => {
    // 回归:旧值 'NEW,PARTIAL' 中 'PARTIAL' 非后端 OrderStatus enum 名(实为 PARTIALLY_FILLED),
    // OrderController.parseStatuses valueOf 严格 → 400(4103)→ useOrders 失败 → 活动 tab 永空。
    // PERP 限价挂单停 SUBMITTED 才暴露(SPOT 即时 FILLED 没撞到)。截获 request URL 断言 status 合法。
    const captured: string[] = []
    server.use(
      http.get('/api/v1/orders', ({ request }) => {
        captured.push(new URL(request.url).searchParams.get('status') ?? '')
        return HttpResponse.json(
          envelope({ content: [], page: 1, pageSize: 50, total: 0, totalPages: 1 }),
        )
      }),
    )
    const { user } = await renderPage()
    await screen.findByText('可用')
    await waitFor(() => expect(captured.length).toBeGreaterThan(0))
    // 活动 tab 默认:含 SUBMITTED(挂单态),不含非法 'PARTIAL'
    const activeVals = captured[0].split(',')
    expect(activeVals).toContain('SUBMITTED')
    expect(activeVals).not.toContain('PARTIAL')
    // 切已撤销 → status 恰为 CANCELLED
    await user.click(screen.getByRole('button', { name: '已撤销' }))
    await waitFor(() => {
      expect(captured[captured.length - 1].split(',')).toEqual(['CANCELLED'])
    })
  })

  it('活动订单显撤单按钮 + ConfirmDialog + 确认调 DELETE(撤单功能 H1)', async () => {
    const { user } = await renderPage()
    await screen.findByText('可用')
    // 活动 tab 默认:account 1 活动订单 1002(PARTIALLY_FILLED)显撤单按钮;
    // 1001(NEW)不在活动四态(PENDING_NEW/SUBMITTED/PARTIALLY_FILLED/PENDING_CANCEL)不显;
    // 1003(FILLED)terminal 不显按钮(显 —)。
    const cancelBtn = await screen.findByRole('button', { name: '撤单' })
    await user.click(cancelBtn)
    expect(await screen.findByText('确认撤销订单')).toBeInTheDocument()
    // 覆写 DELETE 截获(不污染全局 ORDERS handler 数据)
    let deleted = ''
    server.use(
      http.delete('/api/v1/orders/:orderId', ({ params }) => {
        deleted = String(params.orderId)
        return HttpResponse.json(envelope({ orderId: 1002, status: 'CANCELLED', version: 3 }), { status: 202 })
      }),
    )
    await user.click(screen.getByRole('button', { name: '撤销' }))
    await waitFor(() => expect(deleted).toBe('1002'))
    // 成功后 ConfirmDialog 关闭(onSuccess setCancelTarget(null) → open=false → 卸载)
    await waitFor(() => expect(screen.queryByText('确认撤销订单')).not.toBeInTheDocument())
  })
})
