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
  ArrowDown,
  Check,
  Lightbulb,
  ShieldAlert,
  Sparkles,
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
import { SparklineChart } from '@/components/charts/SparklineChart'
import { usePortfolioSummary, usePortfolioPnl, usePortfolioEquityCurve } from '@/hooks/usePortfolio'
import {
  useStrategies,
  usePauseStrategy,
  useStartStrategy,
} from '@/hooks/useStrategies'
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
 *  - EquityCurve → usePortfolioEquityCurve(⚠ mock,TD-003 后端无 portfolio/equity-curve 端点)
 *  - 策略行 pnl/version/lines → StrategyDetailDto 无(TD-007):pnl 占位 "—",version 占位 "v1",lines 删
 *    (PositionPnl 无 strategyId,无法按策略聚合;待后端补策略持仓聚合端点 or TradingPage 阶段)
 *  - 4 Stat(累计收益/夏普/最大回撤/胜率)+ Hero "7 天 +12.43%" → 后端无 dashboard 聚合端点(TD-006):
 *    静态占位文案(照原型数字),待后端补 GET /portfolio/dashboard-summary 或类似
 *  - 实时动态 feed 6 条 → 硬编码(照原型),notifStore WS 接通后替换(layout 阶段,不预建 YAGNI)
 *  - 30D/90D/YTD/All tab → 视觉态(equityCurve mock 一份,切 tab 不换数据,待后端补范围参数)
 *
 * 金额:totalEquity/uPnl/paperEquity/liveEquity 全 toDecimal + formatMoney,展示全 kq-mono-row。
 * 涨跌(uPnl)用 pnlArrow + pnlTextClass(a11y 箭头+色,不靠色单独表达),入参 toDecimal().toNumber()。
 * 图标全 lucide-react(原型 ❯❯/∿/⌬/⚡/◇/✓/∠/⛨/↓/▶/✦ 换 Code/Activity/Cpu/Zap/Hexagon/Check/
 * Lightbulb/ShieldAlert/ArrowDown/Play/Sparkles),不用 emoji。
 * 破坏性操作:暂停/启动策略补 ConfirmDialog destructive(CLAUDE.md 硬要求,原型只 toast 无 modal)。
 */
type StrategyDetailDto = components['schemas']['StrategyDetailDto']
type EquityPointDto = components['schemas']['EquityPointDto']

/** 旅程 5 步(照原型 JOURNEY,id 保留原型语义;onClick 经 JOURNEY_ROUTE 映射到脚手架路由)。 */
const JOURNEY = [
  { id: 'strategy', step: 1, label: '编码策略', desc: 'Monaco 编辑器 + AI 流式对话', Icon: Code, state: 'continue' as const },
  { id: 'backtest', step: 2, label: '回测验证', desc: '权益曲线 · 7 项指标 · 多报告对比', Icon: Activity, state: 'continue' as const },
  { id: 'paper', step: 3, label: '模拟验证', desc: 'PAPER 10 万 USDT · 真实撮合', Icon: Cpu, state: 'continue' as const },
  { id: 'live', step: 4, label: '实盘上线', desc: '真实账户 · 策略 Worker 自动', Icon: Zap, state: 'ready' as const },
  { id: 'portfolio', step: 5, label: '持续监控', desc: '组合 · 持仓 · 通知实时推送', Icon: Hexagon, state: 'active' as const },
]

/** 原型 id(paper/live)在脚手架无独立路由,模拟与实盘都在 /trade(TradingPage PAPER/LIVE 模式切换)。 */
const JOURNEY_ROUTE: Record<string, string> = {
  strategy: '/strategy',
  backtest: '/backtest',
  paper: '/trade',
  live: '/trade',
  portfolio: '/portfolio',
}

/** 后端大写枚举 → StrategyStatusBadge 小写(READY→draft/ERROR→stopped 近似,MAP 只有 4 态)。 */
function statusToBadge(s: string): string {
  const m: Record<string, string> = {
    RUNNING: 'running',
    PAUSED: 'paused',
    STOPPED: 'stopped',
    DRAFT: 'draft',
    READY: 'draft',
    ERROR: 'stopped',
  }
  return m[s] ?? s.toLowerCase()
}

/** 实时动态 feed(照原型 6 条,硬编码;notifStore WS 接通后替换)。 */
const ACTIVITY_FEED = [
  { tone: 'up', Icon: Check, title: 'BTC/USDT BUY 0.42 @ 61200', sub: 'PAPER · 全部成交', ts: '14:02' },
  { tone: 'accent', Icon: Lightbulb, title: 'AI 建议优化 ATR 止损倍数', sub: 'BTC Trend Rider', ts: '14:01' },
  { tone: 'warning', Icon: ShieldAlert, title: '风控拦截 o-9006', sub: '触发 MAX_NOTIONAL', ts: '13:58' },
  { tone: 'down', Icon: ArrowDown, title: 'ETH/USDT SHORT -42.10', sub: 'PAPER · 未实现', ts: '13:55' },
  { tone: 'up', Icon: Play, title: 'BTC Trend Rider 启动', sub: 'v1.3.2', ts: '13:30' },
  { tone: 'accent', Icon: Sparkles, title: '回测完成 bt-2201', sub: '+58.4% · 夏普 2.31', ts: '10:42' },
]

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

  const { data: summary, error: summaryError } = usePortfolioSummary()
  const { data: pnl } = usePortfolioPnl()
  const { data: equityCurve } = usePortfolioEquityCurve()
  const { data: strategies, isLoading: stratLoading, error: stratError } = useStrategies()
  const pauseMut = usePauseStrategy()
  const startMut = useStartStrategy()

  const running = (strategies ?? []).filter((s) => s.status === 'RUNNING')
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
        totalEquity={totalEquity}
        uPnl={uPnl}
        uPnlNum={uPnlNum}
        paperEquity={paperEquity}
        liveEquity={liveEquity}
        onNavigate={navigate}
      />

      <JourneyMap onNavigate={navigate} />

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
            (strategies ?? []).map((s) => (
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
          <SectionTitle title="实时动态" sub="WS 推送 · 订单 / 成交 / 持仓" />
          <div className="flex flex-col gap-2">
            {ACTIVITY_FEED.map((a, i) => {
              const AIcon = a.Icon
              return (
                <div
                  key={i}
                  className="flex items-start gap-2.5 rounded-lg bg-surface-card-2 px-2.5 py-2"
                >
                  <div
                    className="flex size-6 shrink-0 items-center justify-center rounded-md bg-surface-card font-bold"
                    style={{ color: TONE_COLOR[a.tone] }}
                  >
                    <AIcon className="size-3" aria-hidden />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-caption font-semibold">{a.title}</div>
                    <div className="text-[10px] text-text-muted">
                      {a.sub} · {a.ts}
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        </Card>
      </div>

      <PerformanceCard equityCurve={equityCurve ?? []} />

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

/** HeroCard — 旅程进行中 Hero(照原型 line 25-62 抄:Chip + h1 + p + 3 按钮 + 右侧总资产/双小卡)。 */
function HeroCard({
  runningCount,
  totalEquity,
  uPnl,
  uPnlNum,
  paperEquity,
  liveEquity,
  onNavigate,
}: {
  runningCount: number
  totalEquity: number
  uPnl: number | string
  uPnlNum: number
  paperEquity: Decimal
  liveEquity: Decimal
  onNavigate: (path: string) => void
}) {
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
            <Chip
              label="旅程进行中 · 第 5 步"
              color="accent"
              className="mb-2.5"
            />
            <span className="mr-1.5 inline-block size-1.5 rounded-full bg-accent align-middle" />
            <h1 className="mt-0 font-medium text-display text-text-primary">
              欢迎回来,<em className="font-display italic text-accent">demo</em>。
            </h1>
            <p className="mt-2.5 max-w-[540px] text-body-sm leading-[1.6] text-text-secondary">
              你有 <strong className="text-up">{runningCount} 个策略</strong>在运行,
              最近 <strong className="text-text-primary">7 天 +12.43%</strong>。继续
              <em className="italic text-text-primary"> BTC Trend Rider</em> 的回测对比,或开始下一个策略。
              {/* honest:runningCount 真数据,"7 天 +12.43%" 占位(TD-006 后端无 dashboard 聚合端点) */}
            </p>
            <div className="mt-[18px] flex flex-wrap gap-2">
              <Button onClick={() => onNavigate('/strategy')}>
                继续编码
                <ArrowRight className="size-4" aria-hidden />
              </Button>
              <Button variant="ghost" onClick={() => onNavigate('/backtest')}>
                对比回测
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
                <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">PAPER</div>
                <div className="kq-mono-row text-[15px] font-bold">
                  $ {formatMoney(paperEquity, { dp: 0 })}
                </div>
              </div>
              <div className="rounded-[10px] border border-border-soft bg-surface-card p-2.5">
                <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">LIVE</div>
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

/** JourneyMap — 策略旅程 5 步(照原型 line 65-95 抄)。 */
function JourneyMap({ onNavigate }: { onNavigate: (path: string) => void }) {
  return (
    <Card className="p-5">
      <SectionTitle
        title="策略旅程"
        sub="编码 → 回测 → 模拟 → 实盘 → 持续监控 · 零割裂"
        right={<Chip label="操作叙事" color="accent" />}
      />
      <div className="flex items-stretch gap-0 overflow-x-auto">
        {JOURNEY.map((j, i) => {
          const JIcon = j.Icon
          const isActive = j.state === 'active'
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
              {i < JOURNEY.length - 1 && (
                <div className="absolute right-[-6px] top-[34px] z-[1] h-0.5 w-3 bg-border-soft" />
              )}
            </div>
          )
        })}
      </div>
    </Card>
  )
}

/** StrategyRow — 单策略行(照原型 line 101-128 抄:名+Badge+元信息+持仓盈亏+Sparkline+编辑+暂停/启动/草稿)。 */
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
  return (
    <div className="grid grid-cols-[1fr_80px_100px_90px_100px] items-center gap-3 border-b border-border-soft py-3 last:border-0 max-[760px]:grid-cols-1 max-[760px]:gap-1.5">
      <div>
        <div className="flex items-center gap-2">
          <strong className="text-body font-semibold text-text-primary">{s.name}</strong>
          <StrategyStatusBadge status={statusToBadge(s.status)} />
        </div>
        {/* honest:s.timeframe→intervalValue 适配;s.version 后端无占位 "v1";s.lines 删(TD-007) */}
        <div className="mt-[3px] text-[11px] text-text-muted">
          {s.symbol} · {s.exchange} · {s.intervalValue} · v1
        </div>
      </div>
      <div>
        <div className="text-[10px] uppercase tracking-[0.04em] text-text-muted">持仓盈亏</div>
        {/* honest:StrategyDetailDto 无 pnl 字段(TD-007),占位 "—" 待后端补策略聚合端点 */}
        <div className="kq-mono-row text-body-sm font-bold text-text-muted">—</div>
      </div>
      <div>
        <SparklineChart data={[1, 2, 3, 2, 4, 3, 5, 4, 6, 7, 5, 8]} width={80} height={24} />
      </div>
      <div>
        <Button variant="ghost" size="sm" className="w-full" onClick={onEdit}>
          编辑
        </Button>
      </div>
      <div className="flex justify-end gap-1">
        {s.status === 'RUNNING' ? (
          <Button
            variant="ghost"
            size="sm"
            className="text-warning"
            onClick={onPause}
          >
            <Pause className="size-3.5" aria-hidden />
            暂停
          </Button>
        ) : s.status === 'PAUSED' ? (
          <Button size="sm" onClick={onStart}>
            <Play className="size-3.5" aria-hidden />
            启动
          </Button>
        ) : (
          <Button
            variant="ghost"
            size="sm"
            onClick={() =>
              toast.warning('草稿模式:需先发布代码版本才能启动')
            }
          >
            <FileCode2 className="size-3.5" aria-hidden />
            草稿
          </Button>
        )}
      </div>
    </div>
  )
}

/** PerformanceCard — 组合权益曲线 + 4 Stat(照原型 line 154-168 抄)。 */
function PerformanceCard({ equityCurve }: { equityCurve: EquityPointDto[] }) {
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
            {/* honest:范围切换为视觉态,切 tab 不换数据(equityCurve mock 一份,TD-003/006 待后端补范围参数) */}
          </Tabs>
        }
      />
      <EquityCurveChart
        data={equityCurve.map((p, i) => [i, p.equity] as [number, number])}
        width={1080}
        height={220}
        color="var(--accent)"
      />
      {/* honest:4 Stat 为占位(后端无 dashboard 聚合端点,TD-006),照原型数字静态展示 */}
      <div className="mt-4 grid grid-cols-4 gap-4 max-[760px]:grid-cols-2">
        <Stat label="累计收益" value="+58.4%" tone="up" mono sub="vs. 基准 +32.1%" />
        <Stat label="夏普比率" value="2.31" mono sub="年化" />
        <Stat label="最大回撤" value="-9.8%" tone="down" mono sub="2026-04" />
        <Stat label="胜率" value="62%" mono sub="184 笔" />
      </div>
    </Card>
  )
}
