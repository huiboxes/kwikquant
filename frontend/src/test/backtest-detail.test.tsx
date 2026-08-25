import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { BacktestDetail } from '@/pages/backtest/BacktestDetail'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

function ui(node: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>,
  )
}

// 只给组件消费到的字段，其余指标渲染对缺省字段容忍
function reportDetailWith(equityCurve: Array<{ equity: number }>, totalReturn: number) {
  return {
    id: 1,
    symbol: 'BTC/USDT',
    timeframe: '1h',
    periodStart: '2026-04-01T00:00:00Z',
    periodEnd: '2026-06-30T00:00:00Z',
    params: null,
    metrics: { totalReturn },
    trades: [],
    equityCurve,
  }
}

function curveStrokes(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('svg path'))
    .map((p) => p.getAttribute('stroke'))
    .filter((s): s is string => s === 'var(--up)' || s === 'var(--down)')
}

describe('BacktestDetail 权益曲线着色', () => {
  it('亏损曲线 → 跌色，不写死涨色', async () => {
    server.use(
      http.get('/api/v1/reports/1', () =>
        HttpResponse.json(
          envelope(
            reportDetailWith(
              [{ equity: 100000 }, { equity: 97000 }, { equity: 94730 }],
              -0.0527,
            ),
          ),
        ),
      ),
    )
    const { container } = ui(<BacktestDetail reportId={1} selectedTaskId={null} tasks={[]} />)
    await waitFor(() => expect(screen.getByText('权益曲线')).toBeInTheDocument())
    expect(curveStrokes(container)).toContain('var(--down)')
    expect(curveStrokes(container)).not.toContain('var(--up)')
  })

  it('盈利曲线 → 涨色', async () => {
    server.use(
      http.get('/api/v1/reports/2', () =>
        HttpResponse.json(
          envelope(
            reportDetailWith(
              [{ equity: 100000 }, { equity: 103000 }, { equity: 108400 }],
              0.084,
            ),
          ),
        ),
      ),
    )
    const { container } = ui(<BacktestDetail reportId={2} selectedTaskId={null} tasks={[]} />)
    await waitFor(() => expect(screen.getByText('权益曲线')).toBeInTheDocument())
    expect(curveStrokes(container)).toContain('var(--up)')
  })
})
