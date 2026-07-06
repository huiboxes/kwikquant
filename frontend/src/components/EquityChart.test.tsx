import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { EquityChart } from './EquityChart'
import { createChart, AreaSeries } from 'lightweight-charts'

vi.mock('lightweight-charts', () => ({
  createChart: vi.fn(() => ({
    addSeries: vi.fn(() => ({ setData: vi.fn(), applyOptions: vi.fn() })),
    applyOptions: vi.fn(),
    timeScale: () => ({ fitContent: vi.fn() }),
    remove: vi.fn(),
  })),
  AreaSeries: { __isArea: true },
}))

beforeAll(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
  vi.stubGlobal(
    'MutationObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
})

describe('EquityChart', () => {
  it('渲染容器(role=img + aria-label=权益曲线)', () => {
    const { container } = render(<EquityChart equityCurve={[]} />)
    expect(container.firstChild).not.toBeNull()
    expect(container.firstChild).toHaveAttribute('role', 'img')
    expect(container.firstChild).toHaveAttribute('aria-label', '权益曲线')
  })

  it('用 AreaSeries 创建 series(非 LineSeries)', async () => {
    render(<EquityChart equityCurve={[]} />)
    await waitFor(() => {
      const chart = vi.mocked(createChart).mock.results[0]?.value
      expect(chart.addSeries).toHaveBeenCalledWith(
        AreaSeries,
        expect.objectContaining({ lineWidth: 2 }),
      )
    })
  })

  it('AreaSeries 渐变色 topColor/bottomColor 是 rgba(从 --color-accent 派生)', async () => {
    render(<EquityChart equityCurve={[]} />)
    await waitFor(() => {
      const chart = vi.mocked(createChart).mock.results[0]?.value
      const arg = chart.addSeries.mock.calls[0]?.[1]
      expect(arg).toHaveProperty('topColor')
      expect(arg).toHaveProperty('bottomColor')
      expect(arg.topColor).toMatch(/^rgba\(/)
      expect(arg.bottomColor).toMatch(/^rgba\(/)
    })
  })
})
