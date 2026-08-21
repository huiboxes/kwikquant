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
import { StartDialog } from './strategy/StartDialog'
import { useAccounts } from '@/hooks/useAccounts'
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
import { useUiStore, type TradeMode } from '@/stores/uiStore'
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
 * 与原型差异(适配后端契约，逐项说明):
 *  - totalEquity → GET /portfolio/summary → PortfolioSummary.totalUsdt(不 reduce accounts)
 *  - uPnl → GET /portfolio/pnl → PortfolioPnl.totalUnrealizedPnl(不 reduce positions)
 *  - PAPER/LIVE equity 拆分 → summary.accounts 按 exchange==='PAPER' filter + reduce totalUsdt
 *    (AccountSummary 带 exchange='PAPER' 标记 + per-account totalUsdt，可直接拆；原型 accounts.equity 无对应字段)
 *  - EquityCurve → usePortfolioEquityCurve(GET /portfolio/equity-curve 真端点)
 *  - 策略行 pnl/version → StrategyDetailDto 无 pnl:pnl 占位 "—";version 用 s.version ?? '--'
 *  - 4 Stat(累计盈亏/交易天数/胜率/累计手续费)→ useTradeHistoryStats 接真 GET /trade-history/stats(非占位)
 *  - Hero 文案 → useHeroCopy 按用户状态动态(新用户/有策略未运行/运行中)，非硬编码"7天+12.43%"
 *  - 实时动态 feed → useActivityFeed 接真 GET /activity-feed(refetchInterval 30s，非硬编码)
 *  - 30D/90D/YTD/All tab → equityCurve 真数据，tab 未 slice(后端无范围参数，暂不支持)
 *  - sparkline → StrategyDetailDto 无策略级 pnl 历史，占位 "—"，待后端补策略持仓聚合
 *
 * 金额:totalEquity/uPnl/paperEquity/liveEquity 全 toDecimal + formatMoney，展示全 kq-mono-row。
 * 涨跌(uPnl)用 pnlArrow + pnlTextClass(a11y 箭头+色，不靠色单独表达)，入参 toDecimal().toNumber()。
 * 图标全 lucide-react(原型 ❯❯/∿/⌬/⚡/◇/✓/∠/⛨/↓/▶/✦ 换 Code/Activity/Cpu/Zap/Hexagon/Check/
 * Lightbulb/ShieldAlert/ArrowDown/Play/Sparkles)，不用 emoji。
 * 破坏性操作：暂停/启动策略补 ConfirmDialog destructive(CLAUDE.md 硬要求，原型只 toast 无 modal)。
 */
type StrategyDetailDto = components['schemas']['StrategyDetailDto']
type EquityPointDto = components['schemas']['EquityPointDto']
type AccountSummary = components['schemas']['AccountSummary']
type ExchangeAccountView = components['schemas']['ExchangeAccountView']

/** 旅程 5 步定义(state 由 useJourneyState 根据用户数据动态计算，不硬编码)。 */
const JOURNEY = [
  { id: 'strategy', step: 1, label: '编写策略', desc: '用代码表达你的交易思路', Icon: Code },
  { id: 'backtest', step: 2, label: '回测验证', desc: '用历史数据检验策略表现', Icon: Activity },
  { id: 'paper', step: 3, label: '模拟验证', desc: '用真实行情和虚拟资金试运行', Icon: Cpu },
  { id: 'live', step: 4, label: '实盘上线', desc: '接入真实账户自动执行', Icon: Zap },
  { id: 'portfolio', step: 5, label: '持续监控', desc: '跟踪收益与风险实时掌握', Icon: Hexagon },
]

type JourneyStepId = 'strategy' | 'backtest' | 'paper' | 'live' | 'portfolio'

/**
 * 根据用户实际状态计算当前旅程活跃步骤(绿点位置)。
 * - 有 LIVE 运行中策略 → live
 * - 有 PAPER 运行中策略 → paper
 * - 有策略但都没运行 → strategy(引导继续优化)
 * - 无任何策略 → null(新用户，不亮绿点)
 */
function useActiveJourneyStep(
  strategies: StrategyDetailDto[],
  accountModes: Map<number, TradeMode>,
): JourneyStepId | null {
  if (strategies.length === 0) return null
  const hasLiveRunning = strategies.some(
    (s) => s.status === 'RUNNING' && accountModes.get(s.exchangeAccountId) === 'LIVE',
  )
  if (hasLiveRunning) return 'live'
  const hasPaperRunning = strategies.some(
    (s) => s.status === 'RUNNING' && accountModes.get(s.exchangeAccountId) === 'PAPER',
  )
  if (hasPaperRunning) return 'paper'
  return 'strategy'
}

/** 原型 id(paper/live)在脚手架无独立路由，模拟与实盘都在 /trade(TradingPage PAPER/LIVE 模式切换)。 */
const JOURNEY_ROUTE: Record<string, string> = {
  strategy: '/strategy',
  backtest: '/backtest',
  paper: '/trade',
  live: '/trade',
  portfolio: '/portfolio',
}

/** 后端大写枚举 → StrategyStatusBadge 小写(6 态一一对应，不再近似)。 */
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
  const [confirmLiveJourney, setConfirmLiveJourney] = useState(false)
  const tradeMode = useUiStore((s) => s.tradeMode)
  const setTradeMode = useUiStore((s) => s.setTradeMode)
  const liveConfirmedThisSession = useUiStore((s) => s.liveConfirmedThisSession)
  const setLiveConfirmedThisSession = useUiStore((s) => s.setLiveConfirmedThisSession)

  const { data: summary, error: summaryError } = usePortfolioSummary(tradeMode)
  const { data: pnl } = usePortfolioPnl(tradeMode)
  const { data: equityCurve } = usePortfolioEquityCurve(tradeMode)
  const { data: strategies, isLoading: stratLoading, error: stratError } = useStrategies()
  const { data: stats } = useTradeHistoryStats({ mode: tradeMode })
  const { data: activityFeed } = useActivityFeed(10)
  const { data: lastEditedStrategy } = useLastEditedStrategy()
  const pauseMut = usePauseStrategy()
  const startMut = useStartStrategy()
  const { data: accounts } = useAccounts()

  const handleStart = (accountId: number) => {
    if (!startTarget) return
    startMut.mutate({ id: startTarget.id, accountId }, {
      onSuccess: () => {
        toast.success(`策略已启动:${startTarget.name}`)
        setStartTarget(null)
      },
      onError: () => toast.error('启动失败，请重试'),
    })
  }

  const accountById = new Map((accounts ?? []).map((account) => [account.id, account]))
  const accountModes = new Map(
    (accounts ?? []).map((account) => [account.id, account.paperTrading ? 'PAPER' : 'LIVE'] as const),
  )

  // Journey/Hero 用全量策略判断用户阶段(不受 tradeMode 过滤影响)
  const activeStep = useActiveJourneyStep(strategies ?? [], accountModes)

  // 按 tradeMode 过滤策略列表(仅用于数据展示区：策略行/PaperLive equity 拆分)
  const filteredStrategies = (strategies ?? []).filter(
    (s) => accountModes.get(s.exchangeAccountId) === tradeMode || !accountModes.has(s.exchangeAccountId),
  )
  const running = filteredStrategies.filter((s) => s.status === 'RUNNING')
  // Hero 概览用全量运行数(不受 tradeMode 过滤，对齐 line 165 注释意图),
  // 避免 PAPER 模式看不到实盘运行策略误显"没在运行"
  const allRunning = (strategies ?? []).filter((s) => s.status === 'RUNNING')
  const uPnl = pnl?.totalUnrealizedPnl ?? 0
  const uPnlNum = toDecimal(uPnl).toNumber()
  // 可用资金(USDT)口径:summary.accounts 各账户 USDT total 之和(平台 USDT 本位，不折算非
  // USDT 估值，与 Portfolio 表头同口径对齐；不再用 summary.totalUsdt 含非 USDT 折算)。
  // PAPER/LIVE 拆分按 paperTrading 标志(模拟盘建号禁止 exchange=PAPER,exchange 不承载模式语义)。
  // 金额红线：聚合用 decimal.js .plus()，不用 JS +(若后端返 string,JS + 会字符串拼接)。
  const usdtTotalOf = (a: AccountSummary) =>
    toDecimal(a.balances?.find((b) => b.currency === 'USDT')?.total ?? 0)
  const paperEquity = (summary?.accounts ?? [])
    .filter((a) => a.paperTrading)
    .reduce((sum, a) => sum.plus(usdtTotalOf(a)), toDecimal(0))
  const liveEquity = (summary?.accounts ?? [])
    .filter((a) => !a.paperTrading)
    .reduce((sum, a) => sum.plus(usdtTotalOf(a)), toDecimal(0))
  const totalEquity = paperEquity.plus(liveEquity)

  const handleJourneyNavigate = (step: JourneyStepId) => {
    if (step === 'paper') {
      setTradeMode('PAPER')
      navigate('/trade')
      return
    }
    if (step === 'live') {
      if (!liveConfirmedThisSession) {
        setConfirmLiveJourney(true)
        return
      }
      setTradeMode('LIVE')
      navigate('/trade')
      return
    }
    navigate(JOURNEY_ROUTE[step])
  }

  // 主聚合 error 兜底(summary/strategies 任一失败 → ErrorState，不白屏)。脱敏通用文案，不裸透底层错误
  if (summaryError || stratError) {
    return (
      <ErrorState
        title="加载失败"
        message="暂时无法加载仪表盘数据，请稍后重试"
        onRetry={() => window.location.reload()}
      />
    )
  }

  return (
    <div className="flex flex-col gap-5">
      <HeroCard
        runningCount={allRunning.length}
        totalStrategies={(strategies ?? []).length}
        totalEquity={totalEquity}
        uPnl={uPnl}
        uPnlNum={uPnlNum}
        paperEquity={paperEquity}
        liveEquity={liveEquity}
        lastEditedStrategy={lastEditedStrategy ?? null}
        onNavigate={navigate}
      />

      <JourneyMap activeStep={activeStep} onNavigate={handleJourneyNavigate} />

      <div className="grid grid-cols-[1.6fr_1fr] gap-5 max-[980px]:grid-cols-1">
        {/* 运行中策略卡 */}
        <Card className="p-5">
          <SectionTitle
            title="运行中策略"
            sub={`${running.length} 个 · 实时持仓更新`}
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
            <div className="flex flex-col items-center gap-2 py-6 text-center">
              {/* 与策略工作台空态一致：模板优先(fork 即用 + 自动首回测)，空策略是次选 */}
              <p className="text-body-sm text-text-secondary">还没有策略。从模板起步，或创建一个空策略。</p>
              <div className="flex gap-2">
                <Button size="sm" onClick={() => navigate('/templates')}>
                  浏览模板
                </Button>
                <Button variant="outline" size="sm" onClick={() => navigate('/strategy?create=1')}>
                  创建策略
                </Button>
              </div>
            </div>
          ) : (
            filteredStrategies.map((s) => (
              <StrategyRow
                key={s.id}
                s={s}
                account={accountById.get(s.exchangeAccountId)}
                onPause={() => setPauseTarget(s)}
                onStart={() => {
                  if (s.status === 'PAUSED' || s.status === 'ERROR') {
                    // resume/重试：用已绑账户，不弹 StartDialog
                    startMut.mutate({ id: s.id }, {
                      onSuccess: () => toast.success(`策略已启动：${s.name}`),
                      onError: () => toast.error('启动失败，请重试'),
                    })
                  } else {
                    setStartTarget(s)
                  }
                }}
                onEdit={() => {
                  navigate(`/strategy?strategyId=${s.id}`)
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
                    <div className="text-caption-xs text-text-muted">
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

      {/* 暂停策略 ConfirmDialog(原型只 toast，移植补 destructive 确认；调 usePauseStrategy) */}
      <ConfirmDialog
        open={pauseTarget != null}
        onOpenChange={(o) => {
          if (!o) setPauseTarget(null)
        }}
        title="确认暂停策略"
        description={`暂停 ${pauseTarget?.name ?? ''}，策略将停止下单但保留持仓与状态。可在主页或策略页重新启动。`}
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
            onError: () => toast.error('暂停失败，请重试'),
          })
        }}
      />

      {/* 启动策略 StartDialog(选账户：去 UNIQUE 后同 exchange 多账户，显式选模拟盘/实盘) */}
      <StartDialog
        open={startTarget != null}
        onOpenChange={(o) => {
          if (!o) setStartTarget(null)
        }}
        strategy={startTarget}
        accounts={(accounts ?? []).filter((a) => a.exchange === startTarget?.exchange)}
        starting={startMut.isPending}
        onStart={handleStart}
      />
      <ConfirmDialog
        open={confirmLiveJourney}
        onOpenChange={setConfirmLiveJourney}
        title="进入实盘交易"
        description="实盘模式会显示真实账户、订单和持仓；后续下单或启动策略可能使用真实资金。"
        confirmLabel="确认进入实盘"
        destructive
        onConfirm={() => {
          setLiveConfirmedThisSession(true)
          setTradeMode('LIVE')
          setConfirmLiveJourney(false)
          navigate('/trade')
        }}
      />
    </div>
  )
}

/**
 * 根据用户状态生成 Hero 区引导文案。
 * - 无策略：新用户引导，不提"回来"或"旅程进行中"
 * - 有策略但无运行中：鼓励启动
 * - 有运行中策略：展示运行状态
 */
function useHeroCopy(runningCount: number, totalStrategies: number, lastEditedName?: string | null) {
  const isNewUser = totalStrategies === 0
  const hasRunning = runningCount > 0

  if (isNewUser) {
    return {
      chip: null,
      greeting: '开始你的量化交易',
      description: '从编写第一个策略开始，经历回测、模拟验证到实盘上线的完整旅程。',
      primaryAction: { label: '创建第一个策略', path: '/strategy' },
    } as const
  }

  if (!hasRunning) {
    return {
      chip: `${totalStrategies} 个策略 · 未运行`,
      greeting: '欢迎回来',
      description: `你有 ${totalStrategies} 个策略，但都没有在运行。${lastEditedName ? `继续编辑「${lastEditedName}」，或` : ''}选择一个策略启动。`,
      primaryAction: { label: lastEditedName ? `继续「${lastEditedName}」` : '管理策略', path: '/strategy' },
    } as const
  }

  return {
    chip: `${runningCount} 个策略运行中`,
    greeting: '欢迎回来',
    description: `你有 ${runningCount} 个策略正在运行。${lastEditedName ? `继续编辑「${lastEditedName}」，或` : ''}查看实时动态。`,
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
  totalEquity: Decimal
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
              <div className="text-caption-sm font-semibold uppercase tracking-[0.05em] text-text-muted">
                USDT 总权益
              </div>
              <div className="kq-mono-row mt-1 text-h1 font-bold tracking-[-0.02em]">
                $ {formatMoney(totalEquity)}
              </div>
              <div
                className={`kq-mono-row mt-0.5 text-caption font-semibold ${pnlTextClass(uPnlNum)}`}
              >
                {pnlArrow(uPnlNum)} {formatMoney(toDecimal(uPnl), { sign: true })} 未实现
              </div>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div className="rounded-[10px] border border-border-soft bg-surface-card p-2.5">
                <div className="text-caption-xs uppercase tracking-[0.05em] text-text-muted">模拟</div>
                <div className="kq-mono-row text-kpi-sm font-bold">
                  $ {formatMoney(paperEquity, { dp: 0 })}
                </div>
              </div>
              <div className="rounded-[10px] border border-border-soft bg-surface-card p-2.5">
                <div className="text-caption-xs uppercase tracking-[0.05em] text-text-muted">实盘</div>
                <div
                  className="kq-mono-row text-kpi-sm font-bold text-accent"
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

/** JourneyMap — 策略旅程 5 步：卡片有间距+连接线+悬浮预留空间，绿点由 activeStep 动态决定。 */
function JourneyMap({
  activeStep,
  onNavigate,
}: {
  activeStep: JourneyStepId | null
  onNavigate: (step: JourneyStepId) => void
}) {
  return (
    <Card className="p-5">
      <SectionTitle
        title="策略旅程"
        sub="从编写到上线的完整流程"
      />
      {/* pt-1 预留悬浮 translate-y 空间，防止卡片顶部被 overflow 裁切 */}
      <div className="grid grid-cols-2 gap-3 pt-1 sm:flex sm:items-stretch sm:overflow-x-auto">
        {JOURNEY.map((j, i) => {
          const JIcon = j.Icon
          const isActive = j.id === activeStep
          return (
            <div key={j.id} className="relative min-w-0 sm:min-w-[160px] sm:flex-1">
              <button
                type="button"
                onClick={() => onNavigate(j.id as JourneyStepId)}
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
                      <div className="text-caption-xs uppercase tracking-[0.04em] text-text-muted">
                        第 {j.step} 步
                      </div>
                    </div>
                  </div>
                  {isActive && (
                    <span className="kq-pulse size-2 rounded-full bg-up" />
                  )}
                </div>
                <div className="mt-2.5 text-caption-sm leading-[1.4] text-text-muted">{j.desc}</div>
              </button>
              {/* 连接线：卡片之间的虚线，最后一张不画；移动端 2-up 网格用 gap 分隔，隐藏虚线 */}
              {i < JOURNEY.length - 1 && (
                <div className="absolute right-[-9px] top-1/2 z-[1] hidden h-px w-[15px] border-t border-dashed border-border-soft sm:block" />
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
  account,
  onPause,
  onStart,
  onEdit,
}: {
  s: StrategyDetailDto
  account?: ExchangeAccountView
  onPause: () => void
  onStart: () => void
  onEdit: () => void
}) {
  const navigate = useNavigate()
  const versionLabel = s.version ?? '--'
  // 绑定账户被删等场景：不再显"账户模式未知"这种开发态文案，给可点击的下一步(红线②)
  const accountMode = account
    ? account.paperTrading
      ? '模拟盘'
      : account.testnet
        ? '测试网'
        : '实盘'
    : null

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
    <div className="grid grid-cols-[1fr_80px_90px_100px] items-center gap-3 border-b border-border-soft py-3 last:border-0 max-[760px]:grid-cols-1 max-[760px]:gap-1.5">
      <div>
        <div className="flex items-center gap-2">
          <strong className="text-body font-semibold text-text-primary">{s.name}</strong>
          <StrategyStatusBadge status={statusToBadge(s.status)} />
        </div>
        <div className="mt-[3px] text-caption-sm text-text-muted">
          {accountMode != null ? (
            <>
              {s.symbol} · {accountMode} · {s.exchange} · {s.intervalValue} · {versionLabel}
            </>
          ) : (
            <>
              {s.symbol} · {s.intervalValue} ·{' '}
              <button
                type="button"
                className="text-accent underline-offset-2 hover:underline"
                onClick={() => navigate('/settings?tab=accounts')}
              >
                请先绑定交易所账户 →
              </button>
            </>
          )}
        </div>
      </div>
      <div>
        <div className="text-caption-xs uppercase tracking-[0.04em] text-text-muted">持仓盈亏</div>
        {/* StrategyDetailDto.pnl 暂返回 null，待 orders 表加 strategy_id 后聚合 */}
        <div className="kq-mono-row text-body-sm font-bold text-text-muted">—</div>
      </div>
      <div>
        {/* DRAFT 时"编辑"和右侧"编辑代码"功能重复，DRAFT 隐藏此按钮避免冗余 */}
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
              <TabsTrigger value="30D">30 天</TabsTrigger>
              <TabsTrigger value="90D">90 天</TabsTrigger>
              <TabsTrigger value="YTD">今年</TabsTrigger>
              <TabsTrigger value="All">全部</TabsTrigger>
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
