import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { SettingsPage } from '@/pages/SettingsPage'

/**
 * SettingsPage 组件测(照 brief §完成标准 3 用例 + Task 4 交易账户 tab 3 用例)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/settings.ts 9 端点 + handlers/account.ts accounts/balance/reset)。
 * 用 userEvent(Radix Tabs 响应 pointerDown,fireEvent.click 不可靠)。
 * renderPage 包 MemoryRouter(Task 4 起 SettingsPage 用 useSearchParams 读 ?tab= 深链)。
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
  it('渲染 header + 5 tab(含交易账户),默认 llm tab 可见', async () => {
    await renderPage()
    expect(screen.getByText('设置')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /LLM API Key/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /MCP 令牌/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /通知偏好/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /交易账户/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /账户与密码/ })).toBeInTheDocument()
    // 默认 llm tab:SectionTitle + 添加按钮
    expect(screen.getByText('添加 Key')).toBeInTheDocument()
    // MSW 返回 2 个 LLM key
    await waitFor(() => {
      expect(screen.getByText('gpt-5 风格策略')).toBeInTheDocument()
      expect(screen.getByText('claude 深度分析')).toBeInTheDocument()
    })
  })

  it('添加 Key modal 打开-关闭', async () => {
    const { user } = await renderPage()
    await screen.findByText('gpt-5 风格策略') // 等 llm tab 渲染稳
    await user.click(screen.getByRole('button', { name: '添加 Key' }))
    expect(await screen.findByText('添加 LLM API Key')).toBeInTheDocument()
    // 取消关闭
    await user.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => {
      expect(screen.queryByText('添加 LLM API Key')).not.toBeInTheDocument()
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
})
