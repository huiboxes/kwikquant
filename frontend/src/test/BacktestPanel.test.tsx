import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { BacktestPanel } from '@/pages/strategy/BacktestPanel'

// BacktestPanel 在 running 态 early return 前仍调 useBacktestTasksByStrategy/useReportDetail
// (React rules),mock 成无数据态，running=true 时不读 data 只显 progress。
// mockTasks/mockDetail 用 vi.hoisted 提到 hoisted 顶层，让 strategyId 过滤 test 能检查调用参数。
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
    expect(screen.getByText('4,400 / 8,760 根')).toBeInTheDocument()
    // 进度条角色存在(radix Progress render progressbar)
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  it('running + progress=null(首次上报前)→ 显"回测准备中" + 引导文案，无进度条', () => {
    renderPanel({ running: true, progress: null })
    expect(screen.getByText('回测准备中')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(screen.getByText(/逐根 K 线回测/)).toBeInTheDocument()
  })

  it('progress.total=0(防除零)→ 不显百分比，降级准备中', () => {
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
 * 原 bug:useReports(全局最新) 不按 strategyId 过滤，切策略 A→B 仍显 A 的全局最新报告。
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

  it('传 strategyId=128 → useBacktestTasksByStrategy 收 128(按策略查，非全局最新)', () => {
    renderPanel({ strategyId: 128, running: false, progress: null })
    expect(mockTasks).toHaveBeenCalledWith(128)
  })

  it('切 strategyId=129 → useBacktestTasksByStrategy 收 129(query key 变自动 refetch)', () => {
    renderPanel({ strategyId: 129, running: false, progress: null })
    expect(mockTasks).toHaveBeenCalledWith(129)
  })

  it('strategyId=undefined → 传 null 给 hook(不查任何策略，显空态)', () => {
    renderPanel({ running: false, progress: null })
    expect(mockTasks).toHaveBeenCalledWith(null)
  })

  it('tasks 有 RUNNING + 父 running=false + progress=null(刷新场景)→ 列表 task.processedBars/totalBars 兜底显进度态', () => {
    // 刷新后父 backtestTaskId 丢 → running=false + progress=null，但后端列表有 RUNNING task
    // (useBacktestTasksByStrategy refetchInterval 5s 兜底)→ 从 task.processedBars/totalBars 兜底显进度
    mockTasks.mockReturnValue({
      data: [{ id: 1, status: 'RUNNING', reportId: null, processedBars: 4400, totalBars: 8760 }],
      isLoading: false,
      error: null,
    })
    renderPanel({ strategyId: 128, running: false, progress: null })
    // Math.round(4400/8760*100)=50，从 task.processedBars/totalBars 兜底
    expect(screen.getByText('回测进行中 50%')).toBeInTheDocument()
    expect(screen.getByText('4,400 / 8,760 根')).toBeInTheDocument()
    // 无 COMPLETED → useReportDetail 收 null(进度态早 return 但 hooks 仍调)
    expect(mockDetail).toHaveBeenCalledWith(null)
  })

  it('tasks 有 PENDING(无 processedBars)→ 显"回测准备中"(列表兜底，无进度数据)', () => {
    mockTasks.mockReturnValue({
      data: [{ id: 1, status: 'PENDING', reportId: null }],
      isLoading: false,
      error: null,
    })
    renderPanel({ strategyId: 128, running: false, progress: null })
    expect(screen.getByText('回测准备中')).toBeInTheDocument()
  })

  it('tasks 空(无任何 task)→ 显空态"暂无回测结果"', () => {
    mockTasks.mockReturnValue({ data: [], isLoading: false, error: null })
    renderPanel({ strategyId: 128, running: false, progress: null })
    expect(screen.getByText('暂无回测结果')).toBeInTheDocument()
    expect(mockDetail).toHaveBeenCalledWith(null)
  })

  it('父 progress(WS 即时)优先于列表 task.processedBars 兜底', () => {
    mockTasks.mockReturnValue({
      data: [{ id: 1, status: 'RUNNING', reportId: null, processedBars: 100, totalBars: 200 }],
      isLoading: false,
      error: null,
    })
    // 父 progress 4400/8760(WS 即时)优先，列表 100/200 被忽略
    renderPanel({ strategyId: 128, running: true, progress: { processed: 4400, total: 8760 } })
    expect(screen.getByText('回测进行中 50%')).toBeInTheDocument()
    expect(screen.getByText('4,400 / 8,760 根')).toBeInTheDocument()
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

  /**
   * 回归守卫(WS COMPLETED 显示结果):回测完成 → StrategyPage invalidate all → tasks
   * refetch 拿到新 COMPLETED task → latestCompleted.reportId → useReportDetail → 显示结果。
   * 原 bug(问题 2 修复引入):WS COMPLETED 只 invalidate backtestKeys.reports(旧 useReports
   * key)，不含 backtestKeys.tasks → tasks 不 refetch → latestCompleted undefined →
   * "暂无回测结果"(需手动刷新页面才出)。本 test 守"tasks 有 COMPLETED + detail 有数据 →
   * 显示结果 metrics，非空态"。
   */
  it('tasks 有 COMPLETED + detail 有数据 → 显示结果 metrics(非"暂无回测结果")', () => {
    mockTasks.mockReturnValue({
      data: [{ id: 3, status: 'COMPLETED', reportId: 42 }],
      isLoading: false,
      error: null,
    })
    mockDetail.mockReturnValue({
      data: {
        metrics: {
          totalReturn: 0.15,
          sharpeRatio: 1.2,
          maxDrawdown: -0.05,
          winRate: 0.6,
          profitFactor: 1.5,
          totalTrades: 10,
          avgTradeDurationSeconds: 3600,
        },
        equityCurve: [{ equity: 10000 }, { equity: 11500 }],
        trades: [],
      },
      isLoading: false,
      error: null,
    })
    renderPanel({ strategyId: 128, running: false, progress: null })
    expect(screen.getByText('回测结果')).toBeInTheDocument()
    expect(screen.queryByText('暂无回测结果')).not.toBeInTheDocument()
  })
})

/**
 * AI 回测解读入口(P1):结果就绪时渲染"AI 解读"按钮，点击回调 onInterpret(reportId),
 * 父组件(StrategyPage)据此置 interpretRequest + 切会话 tab 自动发问。
 */
describe('BacktestPanel AI 解读入口', () => {
  function seedCompletedReport() {
    mockTasks.mockReturnValue({
      data: [{ id: 3, status: 'COMPLETED', reportId: 42 }],
      isLoading: false,
      error: null,
    })
    mockDetail.mockReturnValue({
      data: {
        metrics: {
          totalReturn: 0.15,
          sharpeRatio: 1.2,
          maxDrawdown: -0.05,
          winRate: 0.6,
          profitFactor: 1.5,
          totalTrades: 10,
          avgTradeDurationSeconds: 3600,
        },
        equityCurve: [{ equity: 10000 }, { equity: 11500 }],
        trades: [],
      },
      isLoading: false,
      error: null,
    })
  }

  beforeEach(() => {
    mockTasks.mockReset()
    mockDetail.mockReset()
    mockTasks.mockReturnValue({ data: undefined, isLoading: false, error: null })
    mockDetail.mockReturnValue({ data: undefined, isLoading: false, error: null })
  })

  it('结果就绪 + onInterpret → 点 AI 解读回调 reportId=42', async () => {
    seedCompletedReport()
    const onInterpret = vi.fn<(reportId: number) => void>()
    renderPanel({ strategyId: 128, running: false, progress: null, onInterpret })
    const btn = await screen.findByRole('button', { name: /AI 解读/ })
    await userEvent.click(btn)
    expect(onInterpret).toHaveBeenCalledWith(42)
  })

  it('未传 onInterpret → 不渲染 AI 解读按钮(详情页独立路由无会话面板场景不受影响)', async () => {
    seedCompletedReport()
    renderPanel({ strategyId: 128, running: false, progress: null })
    await screen.findByText('回测结果')
    expect(screen.queryByRole('button', { name: /AI 解读/ })).not.toBeInTheDocument()
    // 查看详情仍在(布局回归；头部 icon title 同名，用 getAll)
    expect(screen.getAllByRole('button', { name: /查看详情/ }).length).toBeGreaterThanOrEqual(1)
  })

  it('空态(无报告)→ 不渲染 AI 解读(没有可解读对象)', async () => {
    mockTasks.mockReturnValue({ data: [], isLoading: false, error: null })
    const onInterpret = vi.fn<(reportId: number) => void>()
    renderPanel({ strategyId: 128, running: false, progress: null, onInterpret })
    expect(await screen.findByText('暂无回测结果')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /AI 解读/ })).not.toBeInTheDocument()
  })
})
