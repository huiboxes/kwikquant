import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SettingsPage } from '@/pages/SettingsPage'

/**
 * SettingsPage 组件测(照 brief §完成标准 3 用例)。
 * MSW handlers 在 setup.ts 全局 listen(handlers/settings.ts 提供 9 端点)。
 * 用 userEvent(Radix Tabs 响应 pointerDown,fireEvent.click 不可靠)。
 */
async function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={qc}>
      <SettingsPage />
    </QueryClientProvider>,
  )
  return { ...utils, user, qc }
}

describe('SettingsPage', () => {
  it('渲染 header + 4 tab,默认 llm tab 可见', async () => {
    await renderPage()
    expect(screen.getByText('设置')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /LLM API Key/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /MCP 令牌/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /通知偏好/ })).toBeInTheDocument()
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
})
