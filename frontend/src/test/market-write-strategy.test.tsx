import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactElement } from 'react'
import { MarketPage } from '@/pages/MarketPage'

function renderWith(ui: ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MarketPage 行尾策按钮(合约态堵错误入口)', () => {
  it('SPOT tab 显示行尾策按钮', async () => {
    renderWith(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    expect(screen.getAllByLabelText(/写策略/).length).toBeGreaterThan(0)
  })

  it('PERP tab 隐藏行尾策按钮', async () => {
    const user = userEvent.setup()
    renderWith(<MarketPage />)
    await waitFor(() => expect(screen.getAllByText('BTC/USDT').length).toBeGreaterThan(0))
    await user.click(screen.getByRole('tab', { name: '合约' }))
    await waitFor(() => {
      expect(screen.queryAllByLabelText(/写策略/).length).toBe(0)
    })
  })
})
