import { describe, it, expect } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { BacktestPage } from '@/pages/BacktestPage'

function renderWithProviders(ui: React.ReactElement) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BacktestPage', () => {
  it('渲染 Header / list rail / 权益曲线 / MetricGrid / 交易明细', async () => {
    renderWithProviders(<BacktestPage />)

    // Header
    expect(await screen.findByText('回测')).toBeInTheDocument()

    // list rail(6 报告,COMPLETED;#1 #2 ... 出现)
    await waitFor(() => expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThan(0))
    expect(screen.getAllByText(/ETH Mean Reversion/).length).toBeGreaterThan(0)

    // 单报告模式(默认选 #1):权益曲线 + 交易明细
    expect(screen.getByText('权益曲线')).toBeInTheDocument()
    expect(screen.getByText('交易明细')).toBeInTheDocument()

    // MetricGrid 7 格指标(总收益率/夏普比率/最大回撤/胜率/盈亏比/交易数/平均持仓)
    expect(screen.getByText('总收益率')).toBeInTheDocument()
    expect(screen.getByText('夏普比率')).toBeInTheDocument()
    expect(screen.getByText('平均持仓')).toBeInTheDocument()

    // 新回测按钮
    expect(screen.getByRole('button', { name: /新回测/ })).toBeInTheDocument()
  })

  it('点"新回测" → 提交 Modal 打开 → 取消关闭', async () => {
    renderWithProviders(<BacktestPage />)
    await screen.findByText('回测')

    // 点"新回测"按钮 → Modal 打开
    fireEvent.click(screen.getByRole('button', { name: /新回测/ }))
    expect(await screen.findByText('提交新回测')).toBeInTheDocument()
    expect(screen.getByText('开始日期')).toBeInTheDocument()
    expect(screen.getByText('初始资金')).toBeInTheDocument()

    // 取消 → Modal 关闭
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.queryByText('提交新回测')).not.toBeInTheDocument())
  })

  it('点"多报告对比" → checkbox 出现 + 对比表渲染', async () => {
    renderWithProviders(<BacktestPage />)
    await screen.findByText('回测')
    await waitFor(() => expect(screen.getAllByText(/BTC Trend Rider/).length).toBeGreaterThan(0))

    // 点"多报告对比" → checkbox 出现 + compareSel 默认 [1,4] 触发对比
    fireEvent.click(screen.getByRole('button', { name: /多报告对比/ }))
    // checkbox 出现(list rail 每卡 1 个)
    expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0)
    // 对比表出现(compareSel=[1,4] → POST /reports/compare → CompareTable)
    expect(await screen.findByText('多报告并排对比')).toBeInTheDocument()
    expect(screen.getByText('指标')).toBeInTheDocument()
  })
})
