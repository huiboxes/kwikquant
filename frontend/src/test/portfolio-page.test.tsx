import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { PortfolioPage } from '@/pages/PortfolioPage'
import { useAuthStore } from '@/stores/authStore'

/**
 * PortfolioPage 组件测(Task 6 改造后:只读化,账户管理归 Settings)。
 * MSW handlers:handlers/account.ts(accounts/balance)+ handlers/portfolio.ts(summary/pnl/equity)。
 */
async function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <PortfolioPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PortfolioPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      user: { userId: 1, username: 'demo' },
      accessToken: 'x',
    })
  })

  it('只读:不显 接入账户/添加账户 按钮', async () => {
    await renderPage()
    expect(screen.queryByRole('button', { name: /接入账户|添加账户/ })).not.toBeInTheDocument()
  })

  it('只读:账户卡不显 重置/删除 按钮(管理归 Settings)', async () => {
    await renderPage()
    await waitFor(() => {
      expect(screen.queryAllByRole('button', { name: /重置/ })).toHaveLength(0)
      expect(screen.queryAllByRole('button', { name: /删除/ })).toHaveLength(0)
    })
  })

  it('文案:Stat sub 中文 模拟/实盘,不泄露 PAPER/LIVE 枚举', async () => {
    await renderPage()
    // MSW ACCOUNTS: id1/3 PAPER + id2/4 LIVE → "2 模拟 · 2 实盘"
    await waitFor(() => {
      expect(screen.getByText(/2 模拟 · 2 实盘/)).toBeInTheDocument()
    })
    // 用户可见处无 PAPER/LIVE 英文枚举
    expect(screen.queryByText(/\bPAPER\b/)).not.toBeInTheDocument()
    expect(screen.queryByText(/\bLIVE\b/)).not.toBeInTheDocument()
  })

  it('表头:显"可用资金",不显权益曲线(归 Dashboard)', async () => {
    await renderPage()
    await waitFor(() => {
      expect(screen.getByText(/可用资金/)).toBeInTheDocument()
    })
    // 权益曲线归 Dashboard PerformanceCard,Portfolio 删冗余块
    expect(screen.queryByText('组合权益曲线')).not.toBeInTheDocument()
  })

  it('现货持有表显非 USDT + 策略持仓改名(合约)', async () => {
    await renderPage()
    await waitFor(() => {
      expect(screen.getByText('现货持有(非 USDT)')).toBeInTheDocument()
      expect(screen.getByText(/共 1 种/)).toBeInTheDocument()
      expect(screen.getByText('策略持仓(合约)')).toBeInTheDocument()
    })
    // 折叠废弃:不再显"另有 N 种非 USDT 资产"
    expect(screen.queryByText(/另有.*非 USDT/)).not.toBeInTheDocument()
  })
})
