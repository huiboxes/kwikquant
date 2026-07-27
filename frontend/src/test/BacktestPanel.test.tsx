import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BacktestPanel } from '@/pages/strategy/BacktestPanel'

// BacktestPanel 在 running 态 early return 前仍调 useBacktestTasksByStrategy/useReportDetail
// (React rules),mock 成无数据态,running=true 时不读 data 只显 progress。
// mockTasks/mockDetail 用 vi.hoisted 提到 hoisted 顶层,让 strategyId 过滤 test 能检查调用参数。
const { mockTasks, mockDetail } = vi.hoisted(() => ({
  mockTasks: vi.fn<
    (id: number | null) => { data: unknown; isLoading: boolean; error: Error | null }
  >(() => ({ data: undefined, isLoading: false, error: null })),
  mockDetail: vi.fn<
    (id: number | null) => { data: unknown; isLoading: boolean; error: Error | null }
  >(() => ({ data: undefined, isLoading: false, error: null })),
}))

vi.mock('@/hooks/useBacktest', () => ({
  useBacktestTasksByStrategy: mockTasks,
  useReportDetail: mockDetail,
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

/**
 * 按策略过滤守卫(问题 2 回归):BacktestPanel 必须按 strategyId 过滤报告(切策略不残留旧结果)。
 * 原 bug:useReports(全局最新) 不按 strategyId 过滤,切策略 A→B 仍显 A 的全局最新报告。
 * 修复:useBacktestTasksByStrategy(strategyId) → 该策略最新 COMPLETED task.reportId → useReportDetail。
 */
describe('BacktestPanel 按策略过滤', () => {
  beforeEach(() => {
    mockTasks.mockReset()
    mockDetail.mockReset()
    // 默认无数据态(running=false 走空态分支)
    mockTasks.mockReturnValue({ data: undefined, isLoading: false, error: null })
    mockDetail.mockReturnValue({ data: undefined, isLoading: false, error: null })
  })

  it('传 strategyId=128 → useBacktestTasksByStrategy 收 128(按策略查,非全局最新)', () => {
    renderPanel({ strategyId: 128, running: false, progress: null })
    expect(mockTasks).toHaveBeenCalledWith(128)
  })

  it('切 strategyId=129 → useBacktestTasksByStrategy 收 129(query key 变自动 refetch)', () => {
    renderPanel({ strategyId: 129, running: false, progress: null })
    expect(mockTasks).toHaveBeenCalledWith(129)
  })

  it('strategyId=undefined → 传 null 给 hook(不查任何策略,显空态)', () => {
    renderPanel({ running: false, progress: null })
    expect(mockTasks).toHaveBeenCalledWith(null)
  })

  it('无 COMPLETED task → 显空态"暂无回测结果"(不残留上一策略报告)', () => {
    // tasks 全是 PENDING/RUNNING(无 COMPLETED)→ latestCompleted undefined → reportId null → 空态
    mockTasks.mockReturnValue({
      data: [
        { id: 1, status: 'PENDING', reportId: null },
        { id: 2, status: 'RUNNING', reportId: null },
      ],
      isLoading: false,
      error: null,
    })
    renderPanel({ strategyId: 128, running: false, progress: null })
    expect(screen.getByText('暂无回测结果')).toBeInTheDocument()
    // useReportDetail 不应被调 with 任何 reportId(无 COMPLETED)
    expect(mockDetail).toHaveBeenCalledWith(null)
  })

  it('有 COMPLETED task(reportId=42)→ useReportDetail 收 42(按策略最新报告)', () => {
    mockTasks.mockReturnValue({
      data: [
        { id: 3, status: 'COMPLETED', reportId: 42 },
        { id: 2, status: 'COMPLETED', reportId: 41 },
      ],
      isLoading: false,
      error: null,
    })
    renderPanel({ strategyId: 128, running: false, progress: null })
    // 最新在前([0]=reportId 42),useReportDetail 收 42(非 41)
    expect(mockDetail).toHaveBeenCalledWith(42)
  })
})
