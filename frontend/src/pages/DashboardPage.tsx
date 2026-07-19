import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import {
  Code,
  Activity,
  Cpu,
  Zap,
  Hexagon,
  Play,
  Pause,
  FileCode2,
  ArrowRight,
  Check,
  ShieldAlert,
} from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { SectionTitle } from '@/components/SectionTitle'
import { Chip } from '@/components/Chip'
import { Stat } from '@/components/Stat'
import { StrategyStatusBadge } from '@/components/StrategyStatusBadge'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EquityCurveChart } from '@/components/charts/EquityCurveChart'
import { usePortfolioSummary, usePortfolioPnl, usePortfolioEquityCurve } from '@/hooks/usePortfolio'
import {
  useStrategies,
  usePauseStrategy,
  useStartStrategy,
  useLastEditedStrategy,
} from '@/hooks/useStrategies'
import { useActivityFeed } from '@/hooks/useActivityFeed'
import { useTradeHistoryStats } from '@/hooks/useTradeHistory'
import { useUiStore } from '@/stores/uiStore'
import type { Decimal } from 'decimal.js'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * DashboardPage — 主页(照原型 done-design/components/DashboardPage.jsx port)。
 *
 * 旅程引导入口:Hero(总资产/未实现 + PAPER/LIVE 拆分)+ Journey map(5 步 setPage →
 * /strategy /backtest /trade×2 /portfolio)+ 运行中策略卡(暂停/启动补 Confirm)+ 实时动态
 * feed + 组合权益曲线 + 4 Stat。
 *
 * 适配后端契约(honest 差异,不静默照做,记 page 注释 + docs/tech-debt.md TD-006/007):
 *  - totalEquity → GET /portfolio/summary → PortfolioSummary.totalUsdt(不 reduce accounts)
 *  - uPnl → GET /portfolio/pnl → PortfolioPnl.totalUnrealizedPnl(不 reduce positions)
 *  - PAPER/LIVE equity 拆分 → summary.accounts 按 exchange==='PAPER' filter + reduce totalUsdt
 *    (AccountSummary 带 exchange='PAPER' 标记 + per-account totalUsdt,可直接拆;原型 accounts.equity 无对应字段)
 *  - EquityCurve → usePortfolioEquityCurve(TD-003 已接 GET /portfolio/equity-curve 真端点)
 *  - 策略行 pnl/version/lines → StrategyDetailDto 无(TD-007):pnl 占位 "—",version 占位 "v1",lines 删
 *    (PositionPnl 无 strategyId,无法按策略聚合;待后端补策略持仓聚合端点 or TradingPage 阶段)
 *  - 4 Stat(累计收益/夏普/最大回撤/胜率)+ Hero "7 天 +12.43%" → 后端无 dashboard 聚合端点(TD-006):
 *    静态占位文案(照原型数字),待后端补 GET /portfolio/dashboard-summary 或类似
 *  - 实时动态 feed 6 条 → 硬编码(照原型),notifStore WS 接通后替换(layout 阶段,不预建 YAGNI)
 *  - 30D/90D/YTD/All tab → 待补:equityCurve 是真数据,但 tab 未 slice 全量曲线(前端按 range 截后 N 天,后端无范围参数)。非 mock。
 *
 * 金额:totalEquity/uPnl/paperEquity/liveEquity 全 toDecimal + formatMoney,展示全 kq-mono-row。
 * 涨跌(uPnl)用 pnlArrow + pnlTextClass(a11y 箭头+色,不靠色单独表达),入参 toDecimal().toNumber()。
 * 图标全 lucide-react(原型 ❯❯/∿/⌬/⚡/◇/✓/∠/⛨/↓/▶/✦ 换 Code/Activity/Cpu/Zap/Hexagon/Check/
 * Lightbulb/ShieldAlert/ArrowDown/Play/Sparkles),不用 emoji。
 * 破坏性操作:暂停/启动策略补 ConfirmDialog destructive(CLAUDE.md 硬要求,原型只 toast 无 modal)。
 */
type StrategyDetailDto = components['schemas']['StrategyDetailDto']
type EquityPointDto = components['schemas']['EquityPointDto']

/** 旅程 5 步定义(state 由 useJourneyState 根据用户数据动态计算,不硬编码)。 */
const JOURNEY = [
  { id: 'strategy', step: 1, label: '编写策略', desc: '用代码表达你的交易思路', Icon: Code },
  { id: 'backtest', step: 2, label: '回测验证', desc: '用历史数据检验策略表现', Icon: Activity },
  { id: 'paper', step: 3, label: '模拟验证', desc: '真实行情下零风险试运行', Icon: Cpu },
  { id: 'live', step: 4, label: '实盘上线', desc: '接入真实账户自动执行', Icon: Zap },
  { id: 'portfolio', step: 5, label: '持续监控', desc: '跟踪收益与风险实时掌握', Icon: Hexagon },
]

type JourneyStepId = 'strategy' | 'backtest' | 'paper' | 'live' | 'portfolio'

/**
 * 根据用户实际状态计算当前旅程活跃步骤(绿点位置)。
 * - 有 LIVE 运行中策略 → live
 * - 有 PAPER 运行中策略 → paper
 * - 有策略但都没运行 → strategy(引导继续优化)
 * - 无任何策略 → null(新用户,不亮绿点)
 */
function useActiveJourneyStep(
  strategies: StrategyDetailDto[],
): JourneyStepId | null {
  if (strategies.length === 0) return null
  const hasLiveRunning = strategies.some(
    (s) => s.status === 'RUNNING' && s.exchange !== 'PAPER',
  )
  if (hasLiveRunning) return 'live'
  const hasPaperRunning = strategies.some(
    (s) => s.status === 'RUNNING' && s.exchange === 'PAPER',
  )
  if (hasPaperRunning) return 'paper'
  return 'strategy'
}

/** 原型 id(paper/live)在脚手架无独立路由,模拟与实盘都在 /trade(TradingPage PAPER/LIVE 模式切换)。 */
const JOURNEY_ROUTE: Record<string, string> = {
  strategy: '/strategy',
  backtest: '/backtest',
  paper: '/trade',
  live: '/trade',
  portfolio: '/portfolio',
}

/** 后端大写枚举 → StrategyStatusBadge 小写(6 态一一对应,不再近似)。 */
function statusToBadge(s: string): string {
  const m: Record<string, string> = {
    RUNNING: 'running',
    PAUSED: 'paused',
    STOPPED: 'stopped',
    DRAFT: 'draft',
    READY: 'ready',
    ERROR: 'error',
  }
  return m[s] ?? s.toLowerCase()
}

const TONE_COLOR: Record<string, string> = {
  up: 'var(--up)',
  down: 'var(--down)',
  warning: 'var(--warning)',
  accent: 'var(--accent)',
}

export function DashboardPage() {
  const navigate = useNavigate()
  const [pauseTarget, setPauseTarget] = useState<StrategyDetailDto | null>(null)
  const [startTarget, setStartTarget] = useState<StrategyDetailDto | null>(null)
  const tradeMode = useUiStore((s) => s.tradeMode)

  const { data: summary, error: summaryError } = usePortfolioSummary(tradeMode)
  const { data: pnl } = usePortfolioPnl(tradeMode)
  const { data: equityCurve } = usePortfolioEquityCurve(tradeMode)
  const { data: strategies, isLoading: stratLoading, error: stratError } = useStrategies()
  const { data: stats } = useTradeHistoryStats({ mode: tradeMode })
  const { data: activityFeed } = useActivityFeed(10)
  const { data: lastEditedStrategy } = useLastEditedStrategy()
  const pauseMut = usePauseStrategy()
  const startMut = useStartStrategy()

  // Journey/Hero 用全量策略判断用户阶段(不受 tradeMode 过滤影响)
  const activeStep = useActiveJourneyStep(strategies ?? [])

  // 按 tradeMode 过滤策略列表(仅用于数据展示区:策略行/PaperLive equity 拆分)
  const filteredStrategies = (strategies ?? []).filter(
    (s) => tradeMode === 'PAPER' ? s.exchange === 'PAPER' : s.exchange !== 'PAPER',
  )
  const running = filteredStrategies.filter((s) => s.status === 'RUNNING')
  const totalEquity = summary?.totalUsdt ?? 0
  const uPnl = pnl?.totalUnrealizedPnl ?? 0
  const uPnlNum = toDecimal(uPnl).toNumber()
  // PAPER/LIVE equity 拆分:summary.accounts 按 exchange='PAPER' filter + reduce totalUsdt。
  // 金额红线:聚合用 decimal.js .plus(),不用 JS +(若后端返 string "100000",JS + 会字符串拼接)。
  const paperEquity = (summary?.accounts ?? [])
    .filter((a) => a.exchange === 'PAPER')
    .reduce((sum, a) => sum.plus(toDecimal(a.totalUsdt ?? 0)), toDecimal(0))
  const liveEquity = (summary?.accounts ?? [])
    .filter((a) => a.exchange !== 'PAPER')
    .reduce((sum, a) => sum.plus(toDecimal(a.totalUsdt ?? 0)), toDecimal(0))

  // 主聚合 error 兜底(summary/strategies 任一失败 → ErrorState,不白屏)
  if (summaryError || stratError) {
    return (
      <ErrorState
        title="加载失败"
        message={(summaryError ?? stratError ?? new Error('未知错误')).message}
        onRetry={() => window.location.reload()}
      />
    )
  }

  return (
    <div className="flex flex-col gap-5">
      <HeroCard
        runningCount={running.length}
        totalStrategies={(strategies ?? []).length}
        totalEquity={totalEquity}
        uPnl={uPnl}
        uPnlNum={uPnlNum}
        paperEquity={paperEquity}
        liveEquity={liveEquity}
        lastEditedStrategy={lastEditedStrategy ?? null}
        onNavigate={navigate}
      />

      <JourneyMap activeStep={activeStep} onNavigate={navigate} />

      <div className="grid grid-cols-[1.6fr_1fr] gap-5 max-[980px]:grid-cols-1">
        {/* 运行中策略卡 */}
        <Card className="p-5">
          <SectionTitle
            title="运行中策略"
            sub={`${running.length} 个 · 实时持仓推送`}
            right={
              <Button variant="ghost" size="sm" onClick={() => navigate('/strategy')}>
                管理全部
                <ArrowRight className="size-4" aria-hidden />
              </Button>
            }
          />
          {stratLoading ? (
            <LoadingState rows={3} />
          ) : (strategies ?? []).length === 0 ? (
            <div className="py-6 text-center">
              <Button variant="link" size="sm" onClick={() => navigate('/strategy')}>
                暂无策略,先去编码 →
              </Button>
            </div>
          ) : (
            filteredStrategies.map((s) => (
              <StrategyRow
                key={s.id}
                s={s}
                onPause={() => setPauseTarget(s)}
                onStart={() => setStartTarget(s)}
                onEdit={() => {
                  navigate('/strategy')
                  toast.success(`已打开策略:${s.name}`)
                }}
              />
            ))
          )}
        </Card>

        {/* 实时动态 feed */}
        <Card className="p-5">
          <SectionTitle title="实时动态" sub="订单 / 风控 / 策略事件" />
          <div className="flex flex-col gap-2">
            {(activityFeed ?? []).length === 0 && (
              <div className="py-4 text-center text-caption text-text-muted">暂无动态</div>
            )}
            {(activityFeed ?? []).map((a, i) => {
              const iconMap: Record<string, typeof Check> = {
                ORDER_FILLED: Check,
                RISK_TRIGGERED: ShieldAlert,
                STRATEGY_STATE_CHANGED: Play,
              }
              const toneMap: Record<string, string> = {
                ORDER_FILLED: 'up',
                RISK_TRIGGERED: 'warning',
                STRATEGY_STATE_CHANGED: 'accent',
              }
              const AIcon = iconMap[a.type] ?? Activity
              const tone = toneMap[a.type] ?? 'accent'
              const ts = new Date(a.timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
              return (
                <div
                  key={i}
                  className="flex items-start gap-2.5 rounded-lg bg-surface-card-2 px-2.5 py-2"
                >
                  <div
                    className="flex size-6 shrink-0 items-center justify-center rounded-md bg-surface-card font-bold"
                    style={{ color: TONE_COLOR[tone] }}
                  >
                    <AIcon className="size-3" aria-hidden />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-caption font-semibold">{a.title}</div>
                    <div className="text-[10px] text-text-muted">
                      {a.subtitle ?? ''} · {ts}
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        </Card>
      </div>

      <PerformanceCard equityCurve={equityCurve ?? []} stats={stats} />

      {/* 暂停策略 ConfirmDialog(原型只 toast,移植补 destructive 确认;调 usePauseStrategy) */}
      <ConfirmDialog
        open={pauseTarget != null}
        onOpenChange={(o) => {
          if (!o) setPauseTarget(null)
        }}
        title="确认暂停策略"
        description={`暂停 ${pauseTarget?.name ?? ''},策略将停止下单但保留持仓与状态。可在 Dashboard 或策略页重新启动。`}
        confirmLabel="暂停"
        destructive
        loading={pauseMut.isPending}
        onConfirm={() => {
          if (!pauseTarget) return
          pauseMut.mutate(pauseTarget.id, {
            onSuccess: () => {
              toast.success(`策略已暂停:${pauseTarget.name}`)
              setPauseTarget(null)
            },
            onError: () => toast.error('暂停失败,请重试'),
          })
        }}
      />

      {/* 启动策略 ConfirmDialog(启动有风险,补确认;调 useStartStrategy) */}
      <ConfirmDialog
        open={startTarget != null}
        onOpenChange={(o) => {
          if (!o) setStartTarget(null)
        }}
        title="确认启动策略"
        description={`启动 ${startTarget?.name ?? ''},策略将恢复下单。请确认账户余额与风控配置。`}
        confirmLabel="启动"
        loading={startMut.isPending}
        onConfirm={() => {
          if (!startTarget) return
          startMut.mutate(startTarget.id, {
            onSuccess: () => {
              toast.success(`策略已启动:${startTarget.name}`)
              setStartTarget(null)
            },
            onError: () => toast.error('启动失败,请重试'),
          })
        }}
      />
    </div>
  )
}

/**
 * 根据用户状态生成 Hero 区引导文案。
 * - 无策略:新用户引导,不提"回来"或"旅程进行中"
 * - 有策略但无运行中:鼓励启动
 * - 有运行中策略:展示运行状态
 */
function useHeroCopy(runningCount: number, totalStrategies: number, lastEditedName?: string | null) {
  const isNewUser = totalStrategies === 0
  const hasRunning = runningCount > 0

  if (isNewUser) {
    return {
      chip: null,
      greeting: '开始你的量化交易',
      description: '从编写第一个策略开始,经历回测、模拟验证到实盘上线的完整旅程。',
      primaryAction: { label: '创建第一个策略', path: '/strategy' },
    } as const
  }

  if (!hasRunning) {
    return {
      chip: `${totalStrategies} 个策略 · 未运行`,
      greeting: '欢迎回来',
      description: `你有 ${totalStrategies} 个策略,但都没有在运行。${lastEditedName ? `继续编辑「${lastEditedName}」,或` : ''}选择一个策略启动。`,
      primaryAction: { label: lastEditedName ? `继续「${lastEditedName}」` : '管理策略', path: '/strategy' },
    } as const
  }

  return {
    chip: `${runningCount} 个策略运行中`,
    greeting: '欢迎回来',
    description: `你有 ${runningCount} 个策略正在运行。${lastEditedName ? `继续编辑「${lastEditedName}」,或` : ''}查看实时动态。`,
    primaryAction: { label: lastEditedName ? `继续「${lastEditedName}」` : '继续编码', path: '/strategy' },
  } as const
}

/** HeroCard — 根据用户状态动态渲染(不再硬编码"旅程进行中·第5步"/"7天+12.43%"/策略名)。 */
function HeroCard({
  runningCount,
  totalStrategies,
  totalEquity,
  uPnl,
  uPnlNum,
  paperEquity,
  liveEquity,
  lastEditedStrategy,
  onNavigate,
}: {
  runningCount: number
  totalStrategies: number
  totalEquity: number
  uPnl: number | string
  uPnlNum: number
  paperEquity: Decimal
  liveEquity: Decimal
  lastEditedStrategy: StrategyDetailDto | null
  onNavigate: (path: string) => void
}) {
  const copy = useHeroCopy(runningCount, totalStrategies, lastEditedStrategy?.name)

  return (
    <Card className="overflow-hidden p-0">
      <div
        className="px-8 py-7"
        style={{
          background:
            'radial-gradient(circle at 90% 10%, var(--accent-soft) 0%, transparent 55%)',
        }}
      >
        <div className="flex flex-wrap items-start justify-between gap-5">
          <div className="max-w-[600px]">
            {copy.chip && (
              <Chip
                label={copy.chip}
                color="accent"
                className="mb-2.5"
              />
            )}
            <h1 className="mt-0 font-medium text-display text-text-primary">
              {copy.greeting}
            </h1>
            <p className="mt-2.5 max-w-[540px] text-body-sm leading-[1.6] text-text-secondary">
              {copy.description}
            </p>
            <div className="mt-[18px] flex flex-wrap gap-2">
              <Button onClick={() => onNavigate(copy.primaryAction.path)}>
                {copy.primaryAction.label}
                <ArrowRight className="size-4" aria-hidden />
              </Button>
              <Button variant="ghost" onClick={() => onNavigate('/backtest')}>
                回测验证
              </Button>
              <Button variant="ghost" onClick={() => onNavigate('/trade')}>
                打开交易
              </Button>
            </div>
          </div>
          <div className="flex min-w-[240px] flex-col gap-2.5">
            <div className="rounded-xl border border-border-soft bg-surface-card p-3.5">
              <div className="text-[11px] font-semibold uppercase tracking-[0.05em] text-text-muted">
                总资产(USDT 估值)
              </div>
              <div className="kq-mono-row mt-1 text-h1 font-bold tracking-[-0.02em]">
                $ {formatMoney(toDecimal(totalEquity))}
              </div>
              <div
                className={`kq-mono-row mt-0.5 text-caption font-semibold ${pnlTextClass(uPnlNum)}`}
              >
                {pnlArrow(uPnlNum)} {formatMoney(toDecimal(uPnl), { sign: true })} 未实现
              </div>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div className="rounded-[10px] border border-border-soft bg-surface-card p-2.5">
                <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">模拟</div>
                <div className="kq-mono-row text-[15px] font-bold">
                  $ {formatMoney(paperEquity, { dp: 0 })}
                </div>
              </div>
              <div className="rounded-[10px] border border-border-soft bg-surface-card p-2.5">
                <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">实盘</div>
                <div
                  className="kq-mono-row text-[15px] font-bold text-accent"
                >
                  $ {formatMoney(liveEquity, { dp: 0 })}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Card>
  )
}

/** JourneyMap — 策略旅程 5 步:卡片有间距+连接线+悬浮预留空间,绿点由 activeStep 动态决定。 */
function JourneyMap({
  activeStep,
  onNavigate,
}: {
  activeStep: JourneyStepId | null
  onNavigate: (path: string) => void
}) {
  return (
    <Card className="p-5">
      <SectionTitle
        title="策略旅程"
        sub="从编写到上线的完整流程"
      />
      {/* pt-1 预留悬浮 translate-y 空间,防止卡片顶部被 overflow 裁切 */}
      <div className="flex items-stretch gap-3 overflow-x-auto pt-1">
        {JOURNEY.map((j, i) => {
          const JIcon = j.Icon
          const isActive = j.id === activeStep
          return (
            <div key={j.id} className="relative min-w-[160px] flex-1">
              <button
                type="button"
                onClick={() => onNavigate(JOURNEY_ROUTE[j.id] ?? `/`)}
                className="w-full rounded-xl border border-border-soft bg-surface-card-2 p-3.5 text-left transition-all hover:border-accent hover:-translate-y-0.5"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div
                      className={`flex size-7 items-center justify-center rounded-lg text-sm font-bold ${
                        isActive ? 'bg-accent text-on-accent' : 'bg-surface-3 text-text-secondary'
                      }`}
                    >
                      <JIcon className="size-3.5" aria-hidden />
                    </div>
                    <div>
                      <div className="text-caption font-semibold">{j.label}</div>
                      <div className="text-[10px] uppercase tracking-[0.04em] text-text-muted">
                        第 {j.step} 步
                      </div>
                    </div>
                  </div>
                  {isActive && (
                    <span className="kq-pulse size-2 rounded-full bg-up" />
                  )}
                </div>
                <div className="mt-2.5 text-[11px] leading-[1.4] text-text-muted">{j.desc}</div>
              </button>
              {/* 连接线:卡片之间的虚线,最后一张不画 */}
              {i < JOURNEY.length - 1 && (
                <div className="absolute right-[-9px] top-1/2 z-[1] h-px w-[15px] border-t border-dashed border-border-soft" />
              )}
            </div>
          )
        })}
      </div>
    </Card>
  )
}

/** StrategyRow — 单策略行(名+Badge+元信息+持仓盈亏+Sparkline+编辑+状态操作)。 */
function StrategyRow({
  s,
  onPause,
  onStart,
  onEdit,
}: {
  s: StrategyDetailDto
  onPause: () => void
  onStart: () => void
  onEdit: () => void
}) {
  const versionLabel = s.version ?? '--'

  /** 右侧操作按钮：根据状态显示不同语义的操作。 */
  const actionButton = (() => {
    switch (s.status) {
      case 'RUNNING':
        return (
          <Button variant="ghost" size="sm" className="text-warning" onClick={onPause}>
            <Pause className="size-3.5" aria-hidden />
            暂停
          </Button>
        )
      case 'PAUSED':
      case 'READY':
      case 'STOPPED':
        return (
          <Button size="sm" onClick={onStart}>
            <Play className="size-3.5" aria-hidden />
            启动
          </Button>
        )
      case 'ERROR':
        return (
          <Button size="sm" variant="destructive" onClick={onStart}>
            <Play className="size-3.5" aria-hidden />
            重启
          </Button>
        )
      case 'DRAFT':
        return (
          <Button variant="ghost" size="sm" onClick={onEdit}>
            <FileCode2 className="size-3.5" aria-hidden />
            编辑代码
          </Button>
        )
      default:
        return null
    }
  })()

  return (
    <div className="grid grid-cols-[1fr_80px_100px_90px_100px] items-center gap-3 border-b border-border-soft py-3 last:border-0 max-[760px]:grid-cols-1 max-[760px]:gap-1.5">
      <div>
        <div className="flex items-center gap-2">
          <strong className="text-body font-semibold text-text-primary">{s.name}</strong>
          <StrategyStatusBadge status={statusToBadge(s.status)} />
        </div>
        <div className="mt-[3px] text-[11px] text-text-muted">
          {s.symbol} · {s.exchange} · {s.intervalValue} · {versionLabel}
        </div>
      </div>
      <div>
        <div className="text-[10px] uppercase tracking-[0.04em] text-text-muted">持仓盈亏</div>
        {/* honest:StrategyDetailDto.pnl 暂返回 null(TD-007),待 orders 表加 strategy_id 后聚合 */}
        <div className="kq-mono-row text-body-sm font-bold text-text-muted">—</div>
      </div>
      <div>
        {/* TODO: 接入策略级 pnl 历史数据后替换为真实 sparkline */}
        <div className="flex h-[24px] w-[80px] items-center justify-center text-[10px] text-text-muted">—</div>
      </div>
      <div>
        {/* DRAFT 时"编辑"和右侧"编辑代码"功能重复,DRAFT 隐藏此按钮避免冗余 */}
        {s.status !== 'DRAFT' && (
          <Button variant="ghost" size="sm" className="w-full" onClick={onEdit}>
            编辑
          </Button>
        )}
      </div>
      <div className="flex justify-end gap-1">
        {actionButton}
      </div>
    </div>
  )
}

/** PerformanceCard — 组合权益曲线 + 4 Stat(接 trade-history/stats 真实数据)。 */
function PerformanceCard({ equityCurve, stats }: { equityCurve: EquityPointDto[]; stats?: { realizedPnl: number | string; tradingDays: number; winRate: number | null; totalFees: number | string } | null }) {
  const realizedPnl = stats ? toDecimal(stats.realizedPnl) : null
  const pnlTone = realizedPnl && realizedPnl.gte(0) ? 'up' : 'down'
  const winRatePct = stats?.winRate != null ? `${(stats.winRate * 100).toFixed(1)}%` : '--'
  return (
    <Card className="p-5">
      <SectionTitle
        title="组合权益曲线"
        sub="近 30 天 · USDT 估值"
        right={
          <Tabs defaultValue="30D">
            <TabsList>
              <TabsTrigger value="30D">30D</TabsTrigger>
              <TabsTrigger value="90D">90D</TabsTrigger>
              <TabsTrigger value="YTD">YTD</TabsTrigger>
              <TabsTrigger value="All">All</TabsTrigger>
            </TabsList>
          </Tabs>
        }
      />
      <EquityCurveChart
        data={equityCurve.map((p, i) => [i, p.equity] as [number, number])}
        width={1080}
        height={220}
        color="var(--accent)"
      />
      <div className="mt-4 grid grid-cols-4 gap-4 max-[760px]:grid-cols-2">
        <Stat label="累计盈亏" value={realizedPnl ? formatMoney(realizedPnl) : '--'} tone={pnlTone} mono sub="已实现" />
        <Stat label="交易天数" value={stats?.tradingDays != null ? String(stats.tradingDays) : '--'} mono sub="有成交的天数" />
        <Stat label="按日胜率" value={winRatePct} mono sub="盈利天 / 总天数" />
        <Stat label="累计手续费" value={stats ? formatMoney(toDecimal(stats.totalFees)) : '--'} mono sub="USDT" />
      </div>
    </Card>
  )
}
