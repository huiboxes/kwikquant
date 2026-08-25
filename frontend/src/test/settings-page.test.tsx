import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { SettingsPage } from '@/pages/SettingsPage'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

/**
 * SettingsPage 组件测(完成标准 3 用例 + 交易账户 tab 3 用例)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/settings.ts 9 端点 + handlers/account.ts accounts/balance/reset)。
 * 用 userEvent(Radix Tabs 响应 pointerDown,fireEvent.click 不可靠)。
 * renderPage 包 MemoryRouter(SettingsPage 用 useSearchParams 读 ?tab= 深链)。
 */
async function renderPage(initialEntry = '/settings') {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...utils, user, qc }
}

describe('SettingsPage', () => {
  it('渲染 header + 5 tab(含交易账户)，默认 llm tab 可见', async () => {
    await renderPage()
    expect(screen.getByText('设置')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /AI 密钥/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /MCP 令牌/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /通知偏好/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /交易账户/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /账户与密码/ })).toBeInTheDocument()
    // 默认 llm tab:SectionTitle + 添加按钮
    expect(screen.getByText('添加密钥')).toBeInTheDocument()
    // MSW 返回 2 个 LLM key
    await waitFor(() => {
      expect(screen.getByText('gpt-5 风格策略')).toBeInTheDocument()
      expect(screen.getByText('claude 深度分析')).toBeInTheDocument()
    })
  })

  it('添加密钥 modal 打开-关闭', async () => {
    const { user } = await renderPage()
    await screen.findByText('gpt-5 风格策略') // 等 llm tab 渲染稳
    await user.click(screen.getByRole('button', { name: '添加密钥' }))
    expect(await screen.findByText('添加 AI 密钥')).toBeInTheDocument()
    // 取消关闭
    await user.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => {
      expect(screen.queryByText('添加 AI 密钥')).not.toBeInTheDocument()
    })
  })

  it('切到 notif tab 见通知偏好矩阵(6 事件 × 4 渠道)', async () => {
    const { user } = await renderPage()
    await screen.findByText('gpt-5 风格策略')
    await user.click(screen.getByRole('tab', { name: /通知偏好/ }))
    // 6 事件 label
    expect(await screen.findByText('风控拒绝')).toBeInTheDocument()
    expect(screen.getByText('订单成交')).toBeInTheDocument()
    expect(screen.getByText('订单撤销')).toBeInTheDocument()
    expect(screen.getByText('策略启动')).toBeInTheDocument()
    expect(screen.getByText('策略停止')).toBeInTheDocument()
    expect(screen.getByText('策略异常')).toBeInTheDocument()
    // 4 渠道 header
    expect(screen.getByText('站内')).toBeInTheDocument()
    expect(screen.getByText('邮件')).toBeInTheDocument()
    expect(screen.getByText('Telegram')).toBeInTheDocument()
    expect(screen.getByText('Webhook')).toBeInTheDocument()
  })

  it('?tab=accounts 深链 → 交易账户 tab 默认激活', async () => {
    await renderPage('/settings?tab=accounts')
    const tab = await screen.findByRole('tab', { name: /交易账户/ })
    expect(tab).toHaveAttribute('data-state', 'active')
  })

  it('交易账户 tab:显账户列表 + 添加账户按钮', async () => {
    await renderPage('/settings?tab=accounts')
    // MSW account handler 返回的账户 label 之一(主账户 = id 2 LIVE)
    await waitFor(() => expect(screen.getByText('主账户')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /添加账户/ })).toBeInTheDocument()
  })

  it('交易账户 tab:模拟盘账户卡显重置按钮(managed 态)', async () => {
    await renderPage('/settings?tab=accounts')
    // id 1 BINANCE 模拟(paperTrading true)→ AccountCard managed 显重置
    await waitFor(() => expect(screen.getByText('BINANCE 模拟')).toBeInTheDocument())
    expect(screen.getAllByRole('button', { name: /重置/ }).length).toBeGreaterThan(0)
  })

  // ── P1-3 回归：EMAIL 渠道契约对齐 + PUT 失败兜底 ──

  it('P1-3: EMAIL 渠道 checkbox 应 disabled(契约 V1 仅 WEBSOCKET，EMAIL 未实现)', async () => {
    // 回归：旧实现 L336/L691 误放行 EMAIL（可点击 + PUT），后端枚举仅 WEBSOCKET → 400 静默失败。
    // 修复后 EMAIL 与 Telegram/Webhook 同等 disabled，toggle 提示"暂未接入"。
    await renderPage('/settings?tab=notif')
    await screen.findByText('风控拒绝')
    // 找到 EMAIL 列的 checkbox（aria-label 含"邮件"）
    const emailCheckbox = screen.getByRole('checkbox', { name: /风控拒绝 \/ 邮件/ })
    expect(emailCheckbox).toBeDisabled()
  })

  it('P1-3: 站内(WEBSOCKET)渠道 PUT 失败 → 乐观态回滚(checkbox 不残留关闭态)', async () => {
    // 回归：旧 useUpsertNotifPrefs 无 onError，PUT 失败被静默吞掉，乐观态 localOverrides 不回滚
    // → 开关当场显示关闭、刷新后丢失（静默数据丢失）。修复后 onError 回滚 localOverrides。
    // 注：toast 渲染需全局 Toaster(main.tsx 挂载，测试环境未挂)，故此处断言回滚行为而非 toast 文案。
    // fixture：真实后端 400 返 envelope{code:3001,...}（非 0），parseBody 据此抛 ApiError → onError。
    server.use(
      http.put('/api/v1/notifications/preferences', () =>
        HttpResponse.json(envelope(null, 3001, 'Invalid channel type: EMAIL'), { status: 400 }),
      ),
    )
    const { user } = await renderPage('/settings?tab=notif')
    await screen.findByText('风控拒绝')
    const wsCheckbox = screen.getByRole('checkbox', { name: /风控拒绝 \/ 站内/ })
    // 初始 checked=true（EVENT_DEFAULTS.RISK_REJECTED=true × CHANNEL_DEFAULTS.WEBSOCKET=true）
    expect(wsCheckbox).toHaveAttribute('aria-checked', 'true')
    await user.click(wsCheckbox)
    // PUT 失败 → onError 回滚乐观态：checkbox 最终回到 checked=true（不残留关闭态）
    await waitFor(() => {
      expect(wsCheckbox).toHaveAttribute('aria-checked', 'true')
    })
  })
})
