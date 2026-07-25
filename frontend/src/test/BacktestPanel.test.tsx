import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BacktestPanel } from '@/pages/strategy/BacktestPanel'

// BacktestPanel 在 running 态 early return 前仍调 useReports/useReportDetail(React rules),
// mock 成无数据态,running=true 时不读 data 只显 progress。
vi.mock('@/hooks/useBacktest', () => ({
  useReports: () => ({ data: undefined, isLoading: false, error: null }),
  useReportDetail: () => ({ data: undefined, isLoading: false, error: null }),
}))

function renderPanel(props: React.ComponentProps<typeof BacktestPanel>) {
  return render(
    <MemoryRouter>
      <BacktestPanel {...props} />
    </MemoryRouter>,
  )
}

describe('BacktestPanel 进度态', () => {
  it('running + progress → 显百分比 + 进度条 + bar 计数', () => {
    renderPanel({ running: true, progress: { processed: 4400, total: 8760 } })
    // Math.round(4400/8760*100)=50
    expect(screen.getByText('回测进行中 50%')).toBeInTheDocument()
    // toLocaleString 加千分位
    expect(screen.getByText('4,400 / 8,760 bar')).toBeInTheDocument()
    // 进度条角色存在(radix Progress render progressbar)
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  it('running + progress=null(首次上报前)→ 显"回测准备中" + 引导文案,无进度条', () => {
    renderPanel({ running: true, progress: null })
    expect(screen.getByText('回测准备中')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(screen.getByText(/逐 bar 回测/)).toBeInTheDocument()
  })

  it('progress.total=0(防除零)→ 不显百分比,降级准备中', () => {
    renderPanel({ running: true, progress: { processed: 0, total: 0 } })
    expect(screen.getByText('回测准备中')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('running=false → 不显进度态(走结果/空态分支)', () => {
    renderPanel({ running: false, progress: { processed: 4400, total: 8760 } })
    expect(screen.queryByText(/回测进行中|回测准备中/)).not.toBeInTheDocument()
  })
})
