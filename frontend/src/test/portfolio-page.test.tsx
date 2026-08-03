import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { PortfolioPage } from '@/pages/PortfolioPage'
import { useAuthStore } from '@/stores/authStore'
import { useUiStore } from '@/stores/uiStore'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

/**
 * PortfolioPage 组件测(只读化,账户管理归 Settings)。
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
    useUiStore.setState({ tradeMode: 'PAPER' })
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

  it('现货持有表显非 USDT + 跨账户持仓表(回原型命名,不显"(合约)")', async () => {
    useUiStore.setState({ tradeMode: 'LIVE' }) // LIVE 模式返含 BTC 的 accounts → 现货表"共 1 种"
    await renderPage()
    await waitFor(() => {
      expect(screen.getByText('现货持有(非 USDT)')).toBeInTheDocument()
      expect(screen.getByText(/共 1 种/)).toBeInTheDocument()
      expect(screen.getByText('跨账户持仓')).toBeInTheDocument()
    })
    // 折叠废弃:不再显"另有 N 种非 USDT 资产"
    expect(screen.queryByText(/另有.*非 USDT/)).not.toBeInTheDocument()
    // 旧硬编码"(合约)"已去(后端不按 SPOT/PERP 过滤,标题中性化)
    expect(screen.queryByText(/策略持仓/)).not.toBeInTheDocument()
  })

  it('传 tradeMode=PAPER 给 portfolio hooks(修 PortfolioPage 漏传 bug)', async () => {
    let summaryMode: string | null = null
    let pnlMode: string | null = null
    server.use(
      http.get('/api/v1/portfolio/summary', ({ request }) => {
        summaryMode = new URL(request.url).searchParams.get('mode')
        return HttpResponse.json(envelope({ accounts: [], totalEquity: '0', totalFree: '0', totalUsed: '0' }))
      }),
      http.get('/api/v1/portfolio/pnl', ({ request }) => {
        pnlMode = new URL(request.url).searchParams.get('mode')
        return HttpResponse.json(envelope({ positions: [], totalUnrealizedPnl: '0' }))
      }),
    )
    await renderPage()
    await waitFor(() => {
      expect(summaryMode).toBe('PAPER')
      expect(pnlMode).toBe('PAPER')
    })
  })

  it('空态:无持仓时显「去交易页开仓」CTA 引导', async () => {
    server.use(
      http.get('/api/v1/portfolio/pnl', () =>
        HttpResponse.json(envelope({ positions: [], totalUnrealizedPnl: '0' })),
      ),
    )
    await renderPage()
    expect(await screen.findByRole('link', { name: /去交易页开仓/ })).toHaveAttribute('href', '/trade')
  })
})
