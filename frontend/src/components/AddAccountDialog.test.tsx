import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AddAccountDialog } from './AddAccountDialog'
import { useCreateAccount } from '@/hooks/useAccounts'

vi.mock('@/hooks/useAccounts', () => ({
  useCreateAccount: vi.fn(),
}))

describe('AddAccountDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  function renderDialog() {
    const mutate = vi.fn()
    ;(useCreateAccount as ReturnType<typeof vi.fn>).mockReturnValue({ mutate, isPending: false })
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const user = userEvent.setup()
    const utils = render(
      <QueryClientProvider client={qc}>
        <AddAccountDialog open onOpenChange={vi.fn()} createAcc={{ mutate, isPending: false } as never} />
      </QueryClientProvider>,
    )
    return { ...utils, user, mutate }
  }

  it('显模拟盘/实盘双选按钮(不泄露 PAPER/LIVE)', () => {
    renderDialog()
    expect(screen.getByRole('button', { name: '模拟盘' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '实盘' })).toBeInTheDocument()
  })

  it('选实盘 → 显示 API Key/Secret 输入框', async () => {
    const { user } = renderDialog()
    await user.click(screen.getByRole('button', { name: '实盘' }))
    expect(screen.getByPlaceholderText(/粘贴 API key/)).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/粘贴 secret/)).toBeInTheDocument()
  })

  it('说明框不泄露 基准行情撮合 实现细节', () => {
    renderDialog()
    expect(screen.queryByText(/基准行情撮合/)).not.toBeInTheDocument()
  })

  it('点接入调 mutate(body),模拟盘 body 含 paperTrading:true', async () => {
    const { user, mutate } = renderDialog()
    await user.click(screen.getByRole('button', { name: '接入' }))
    await waitFor(() => expect(mutate).toHaveBeenCalledOnce())
    const body = mutate.mock.calls[0][0]
    expect(body.paperTrading).toBe(true)
    expect(body.exchange).toBe('BINANCE')
  })
})
