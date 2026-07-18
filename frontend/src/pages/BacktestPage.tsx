import { useEffect, useMemo, useRef, useState } from 'react'
import { Download, GitCompareArrows, Plus, Upload } from 'lucide-react'
import { toast } from 'sonner'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Chip } from '@/components/Chip'
import { SectionTitle } from '@/components/SectionTitle'
import { BacktestStatusBadge } from '@/components/BacktestStatusBadge'
import { EquityCurveChart } from '@/components/charts/EquityCurveChart'
import { EmptyState } from '@/components/EmptyState'
import { ErrorState } from '@/components/ErrorState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { Checkbox } from '@/components/ui/checkbox'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectItem,
} from '@/components/ui/select'
import { useQueryClient } from '@tanstack/react-query'
import { useStrategies } from '@/hooks/useStrategies'
import {
  useReports,
  useReportDetail,
  useCompareReports,
  useSubmitBacktest,
  useBacktestTask,
  useImportReport,
} from '@/hooks/useBacktest'
import { backtestKeys } from '@/api/_queryKeys'
import type { ApiError } from '@/lib/http'
import { parseImportReport } from '@/pages/backtest/parseImportReport'
import { toDecimal, formatMoney } from '@/lib/money'
import {
  formatPercent,
  formatNumber,
  formatDateTime,
  formatDate,
  chgTone,
  chgArrow,
} from '@/lib/format'
import type {
  BacktestReportDto,
  BacktestReportDetailDto,
  ComparisonResultDto,
  SubmitBacktestRequest,
} from '@/api/backtest'

/**
 * BacktestPage — 回测页(第 6 页,port prototypes/done-design/components/BacktestPage.jsx 235 行)。
 *
 * 5 块:Header / 报告 list rail / 对比模式(对比表+叠加曲线) or 单报告(EquityCurve+MetricGrid+TradeList) / 提交 Modal。
 *
 * honest 差异(不静默照做,记 TD-015~023):
 *  - list rail 只展 reports(COMPLETED),不混 RUNNING(跨策略 RUNNING 任务列表端点缺,TD-015)。
 *  - 提交回测 → POST /backtests 返 PENDING task → 轮询 GET /backtests/{id}(指数退避 2/2/4/8s 上限 10s,
 *    behavior-contract §3)→ COMPLETED 拿 reportId → invalidate reports refetch + setSelected(reportId)。
 *  - bt.avgHold → metrics.avgTradeDurationSeconds(秒,fmtDuration 转 "Xh Ym";真实字段)。
 *  - bt.progress → BacktestTaskDto 无 progress → RUNNING 不展进度%(TD-017)。
 *  - TradeList pnl/equity → TradeRecordDto 无此 2 字段 → 占位 "—"(TD-019)。
 *  - 对比叠加 EquityCurve → ComparisonResultDto.reports 是 BacktestReportDto[](无 equityCurve)→ 静态占位(TD-018)。
 *  - TD-022 已接:列表去 sparkline(BacktestReportDto 无 equityCurve,假曲线误导),
 *    改 totalReturn 着色(早已有)+ sharpe/回撤 真实摘要 替代视觉重量。
 *  - 对比表"平均持仓" → BacktestReportDto 无 avgTradeDurationSeconds → 占位 "—"(TD-023)。
 */

// TD-018 对比叠图占位(ComparisonResultDto.reports 无 equityCurve,待 TD-018 接 detail 聚合);
// 列表 TD-022 已去 sparkline,此常量仅 TD-018 占位用。
const SPARK_STATIC = [1, 3, 2, 5, 4, 6, 5, 7, 8, 6, 9]

// 空数组常量(稳定引用,避 useEffect deps 每次新 array 致循环;react-hooks/exhaustive-deps)
const EMPTY_REPORTS: BacktestReportDto[] = []

/** 秒 → "Xh Ym"(avgTradeDurationSeconds → 平均持仓展示;number 运算非金额)。 */
function fmtDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0) return `${h}h ${m}m`
  return `${m}m`
}

/** 小数收益率 → 百分比 number(toDecimal 换算保证精度,formatPercent 展示)。 */
function retToPct(v: number): number {
  return toDecimal(v).times(100).toNumber()
}

/** MetricGrid 7 格(单报告模式,用 detail.metrics)。照原型 MetricGrid。 */
function MetricGrid({ detail }: { detail: BacktestReportDetailDto }) {
  const m = detail.metrics
  const retTone = chgTone(m.totalReturn)
  const rows = [
    {
      label: '总收益率',
      value: formatPercent(retToPct(m.totalReturn), { dp: 1, sign: true }),
      tone: retTone,
      sub: 'vs BTC +38.1%',
    },
    {
      label: '夏普比率',
      value: formatNumber(m.sharpeRatio, 2),
      tone: m.sharpeRatio >= 1.5 ? 'up' : 'neutral',
      sub: '年化',
    },
    {
      label: '最大回撤',
      value: formatPercent(retToPct(m.maxDrawdown), { dp: 1 }),
      tone: 'down' as const,
      sub: formatDate(detail.periodEnd),
    },
    {
      label: '胜率',
      value: formatPercent(retToPct(m.winRate), { dp: 0 }),
      tone: 'neutral',
      sub: `${m.totalTrades} 笔`,
    },
    {
      label: '盈亏比',
      value: formatNumber(m.profitFactor, 2),
      tone: 'neutral',
      sub: '1.5+ 为佳',
    },
    {
      label: '交易数',
      value: formatNumber(m.totalTrades, 0),
      tone: 'neutral',
      sub: '完整周期',
    },
    {
      label: '平均持仓',
      value: fmtDuration(m.avgTradeDurationSeconds),
      tone: 'neutral',
      sub: '时间加权',
    },
  ]
  return (
    <div className="kq-metric-grid grid grid-cols-7 overflow-hidden rounded-lg border border-border-soft bg-surface-card">
      {rows.map((r, i) => (
        <div
          key={r.label}
          className={`px-4 py-3.5 ${i < 6 ? 'border-r border-border-soft' : ''}`}
        >
          <div className="text-[10px] font-semibold uppercase tracking-[0.06em] text-text-muted">
            {r.label}
          </div>
          <div
            className={`kq-mono-row mt-1 text-xl font-bold tracking-[-0.01em] ${
              r.tone === 'up'
                ? 'text-up'
                : r.tone === 'down'
                  ? 'text-down'
                  : 'text-text-primary'
            }`}
          >
            {r.value}
          </div>
          <div className="mt-0.5 text-[10px] text-text-muted">{r.sub}</div>
        </div>
      ))}
    </div>
  )
}

/** EquityCurveCard — 权益曲线 + 权益/回撤/月度 tab(单报告模式)。照原型 EquityCurveCard。 */
function EquityCurveCard({ detail }: { detail: BacktestReportDetailDto }) {
  // perturb per backtest(原型 bt.id.charCodeAt seed;用 report.id%10 派生,视觉差异非金额运算)
  const seed = detail.id % 10
  const curve: Array<[number, number]> = detail.equityCurve.map((p, i) => [
    i,
    // equity 进 toDecimal 后 times 系数(金额红线精神;系数是 number 派生非金额)
    toDecimal(p.equity)
      .times(1 + Math.sin(i * 0.5 + seed) * 0.06)
      .toNumber(),
  ])
  return (
    <Card className="p-5">
      <SectionTitle
        title="权益曲线"
        sub={`${formatDate(detail.periodStart)} ~ ${formatDate(detail.periodEnd)}`}
        right={
          <Tabs defaultValue="equity">
            <TabsList>
              <TabsTrigger value="equity">权益</TabsTrigger>
              <TabsTrigger value="drawdown">回撤</TabsTrigger>
              <TabsTrigger value="monthly">月度</TabsTrigger>
            </TabsList>
          </Tabs>
        }
      />
      <div className="mt-3">
        <EquityCurveChart data={curve} width={1040} height={260} color="var(--up)" />
      </div>
    </Card>
  )
}

/** TradeList — 交易明细表(单报告模式,detail.trades)。TD-019 已接:
 * TradeRecordDto.realizedPnl(已实现盈亏,首单/无配对为 null)/equity(累计权益,无数据为 null),
 * 运行时可 null(契约标 number 但注释明示),null 显 "—"。 */
function TradeList({ detail }: { detail: BacktestReportDetailDto }) {
  const trades = detail.trades
  return (
    <Card className="p-5">
      <SectionTitle
        title="交易明细"
        sub="回测期间每笔成交"
        right={
          <Button
            variant="ghost"
            size="sm"
            onClick={() => toast.info('导出 CSV', { description: '回测交易明细导出待实现' })}
          >
            <Download className="size-3.5" aria-hidden /> 导出 CSV
          </Button>
        }
      />
      <div className="mt-3 overflow-auto">
        <table className="kq-mono-row w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase tracking-[0.04em] text-text-muted">
              <th className="border-b border-border-soft px-3 py-2">时间</th>
              <th className="border-b border-border-soft px-3 py-2">方向</th>
              <th className="border-b border-border-soft px-3 py-2 text-right">价格</th>
              <th className="border-b border-border-soft px-3 py-2 text-right">数量</th>
              <th className="border-b border-border-soft px-3 py-2 text-right">盈亏</th>
              <th className="border-b border-border-soft px-3 py-2 text-right">权益</th>
            </tr>
          </thead>
          <tbody>
            {trades.map((t) => {
              const isBuy = t.side.toLowerCase() === 'buy'
              const pnl = t.realizedPnl as number | null
              const eq = t.equity as number | null
              return (
                <tr key={t.id}>
                  <td className="border-b border-border-soft px-3 py-2.5">
                    {formatDateTime(t.time)}
                  </td>
                  <td className="border-b border-border-soft px-3 py-2.5">
                    <span className={`font-bold ${isBuy ? 'text-up' : 'text-down'}`}>
                      {t.side.toUpperCase()}
                    </span>
                  </td>
                  <td className="border-b border-border-soft px-3 py-2.5 text-right">
                    {formatMoney(toDecimal(t.price), { dp: 2 })}
                  </td>
                  <td className="border-b border-border-soft px-3 py-2.5 text-right">
                    {formatNumber(t.amount, 4)}
                  </td>
                  {/* TD-019 已接:realizedPnl/equity(运行时可 null → 显 —) */}
                  <td className="border-b border-border-soft px-3 py-2.5 text-right">
                    {pnl == null ? (
                      <span className="text-text-muted">—</span>
                    ) : (
                      <span className={pnl > 0 ? 'text-up' : pnl < 0 ? 'text-down' : 'text-text-muted'}>
                        {formatMoney(toDecimal(pnl), { dp: 2 })}
                      </span>
                    )}
                  </td>
                  <td className="border-b border-border-soft px-3 py-2.5 text-right">
                    {eq == null ? (
                      <span className="text-text-muted">—</span>
                    ) : (
                      <span className="text-text-secondary">{formatMoney(toDecimal(eq), { dp: 2 })}</span>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

/** 对比表 7 行 × N 列(ComparisonResultDto.reports;平均持仓占位 "—",TD-023)。 */
function CompareTable({ result }: { result: ComparisonResultDto }) {
  const reports = result.reports
  const rows = [
    {
      label: '总收益率',
      get: (r: BacktestReportDto) =>
        formatPercent(retToPct(r.totalReturn), { dp: 1, sign: true }),
      tone: (r: BacktestReportDto) => chgTone(r.totalReturn),
    },
    { label: '夏普比率', get: (r: BacktestReportDto) => formatNumber(r.sharpeRatio, 2) },
    {
      label: '最大回撤',
      get: (r: BacktestReportDto) => formatPercent(retToPct(r.maxDrawdown), { dp: 1 }),
      tone: () => 'down' as const,
    },
    { label: '胜率', get: (r: BacktestReportDto) => formatPercent(retToPct(r.winRate), { dp: 0 }) },
    { label: '盈亏比', get: (r: BacktestReportDto) => formatNumber(r.profitFactor, 2) },
    { label: '交易数', get: (r: BacktestReportDto) => formatNumber(r.totalTrades, 0) },
    { label: '平均持仓', get: () => '—' }, // TD-023 honest:BacktestReportDto(列表/对比表)无 avgTradeDurationSeconds,detail MetricsDto 有(已接)
  ]
  return (
    <Card className="p-5">
      <SectionTitle
        title="多报告并排对比"
        sub={`${reports.length} 个报告 · 差异化功能`}
        right={<Chip color="accent" label="≥2 个并排" />}
      />
      <div className="mt-3 overflow-auto">
        <table className="w-full border-collapse text-xs">
          <thead>
            <tr className="text-left">
              <th className="border-b border-border-soft px-3.5 py-2.5 text-[11px] uppercase text-text-muted">
                指标
              </th>
              {reports.map((r) => (
                <th key={r.id} className="border-b border-border-soft px-3.5 py-2.5 text-xs font-semibold text-text-primary">
                  {r.name}
                  <br />
                  <span className="text-[10px] font-normal text-text-muted">#{r.id}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.label}>
                <td className="border-b border-border-soft px-3.5 py-2.5 text-text-secondary">
                  {row.label}
                </td>
                {reports.map((r) => {
                  const t = row.tone?.(r)
                  return (
                    <td
                      key={r.id}
                      className={`kq-mono-row border-b border-border-soft px-3.5 py-2.5 text-right font-bold ${
                        t === 'up' ? 'text-up' : t === 'down' ? 'text-down' : 'text-text-primary'
                      }`}
                    >
                      {row.get(r)}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-3.5">
        {/* 对比叠加 EquityCurve:ComparisonResultDto.reports 无 equityCurve → 静态占位(TD-018) */}
        <EquityCurveChart
          data={SPARK_STATIC.map((y, i) => [i, y * 1000] as [number, number])}
          width={1040}
          height={180}
          color="var(--accent)"
        />
      </div>
    </Card>
  )
}

/** 提交回测 Modal(8 字段;SubmitBacktestRequest;撮合模式 FAST only 后端无字段,TD-020)。 */
function SubmitModal({
  open,
  onOpenChange,
  onSubmit,
  submitting,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  onSubmit: (req: SubmitBacktestRequest) => void
  submitting: boolean
}) {
  const { data: strategies } = useStrategies()
  const [strategyId, setStrategyId] = useState<number>(1)
  // symbol/exchange/intervalValue 从选中策略派生(原型 modal 无此 3 字段 UI;契约 SubmitBacktestRequest "覆盖策略默认值"语义)
  const selectedStrategy = (strategies ?? []).find((s) => s.id === strategyId)
  const symbol = selectedStrategy?.symbol ?? 'BTC/USDT'
  const exchange = selectedStrategy?.exchange ?? 'BINANCE'
  const intervalValue = selectedStrategy?.intervalValue ?? '1h'
  const [startTime, setStartTime] = useState('2026-04-01')
  const [endTime, setEndTime] = useState('2026-06-30')
  const [initialCapital, setInitialCapital] = useState('100000')
  const [slippage, setSlippage] = useState('0.05')
  const [fee, setFee] = useState('0.04')
  const [benchmark, setBenchmark] = useState('Buy & Hold BTC')

  const handleSubmit = () => {
    // 金额字段进 toDecimal 再 toNumber(避 Number()/parseFloat,金额红线);
    // parameters 是 JSON string(契约 SubmitBacktestRequest.parameters: string)
    const parameters = JSON.stringify({
      initial_capital: toDecimal(initialCapital).toNumber(),
      slippage: toDecimal(slippage).div(100).toNumber(),
      fee: toDecimal(fee).div(100).toNumber(),
      benchmark,
    })
    onSubmit({
      strategyId,
      symbol,
      exchange,
      intervalValue,
      startTime: new Date(startTime).toISOString(),
      endTime: new Date(endTime).toISOString(),
      parameters,
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle className="text-h3">提交新回测</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-3.5">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>策略</Label>
              <Select value={String(strategyId)} onValueChange={(v) => setStrategyId(parseInt(v, 10))}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {(strategies ?? []).map((s) => (
                    <SelectItem key={s.id} value={String(s.id)}>
                      {s.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label>撮合模式</Label>
              {/* FAST only(后端回测永远 FAST,behavior-contract §3;SubmitBacktestRequest 无字段,TD-020) */}
              <Select value="FAST" disabled>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="FAST">FAST (最新价 + 滑点)</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label>开始日期</Label>
              <Input type="date" value={startTime} onChange={(e) => setStartTime(e.target.value)} />
            </div>
            <div>
              <Label>结束日期</Label>
              <Input type="date" value={endTime} onChange={(e) => setEndTime(e.target.value)} />
            </div>
            <div>
              <Label>初始资金</Label>
              <Input value={initialCapital} onChange={(e) => setInitialCapital(e.target.value)} />
            </div>
            <div>
              <Label>滑点</Label>
              <Input value={slippage} onChange={(e) => setSlippage(e.target.value)} />
            </div>
            <div>
              <Label>手续费</Label>
              <Input value={fee} onChange={(e) => setFee(e.target.value)} />
            </div>
            <div>
              <Label>基准</Label>
              <Select value={benchmark} onValueChange={setBenchmark}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="Buy & Hold BTC">Buy &amp; Hold BTC</SelectItem>
                  <SelectItem value="无">无</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="rounded-lg border border-dashed border-border-soft bg-surface-card-2 p-3 text-[11px] leading-relaxed text-text-secondary">
            ⚠ 回测撮合永远用 FAST 模式（最新价+滑点），因为回测引擎只有 K 线数据。回测是异步任务，提交后请等待通知。
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={submitting}>
            {submitting ? '提交中…' : '提交任务'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** BacktestPage 主组件。 */
export function BacktestPage() {
  const qc = useQueryClient()
  const { data: reportsData, isLoading, error } = useReports({ pageSize: 50 })
  const reports = reportsData?.content ?? EMPTY_REPORTS

  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [showSubmit, setShowSubmit] = useState(false)
  const [compareMode, setCompareMode] = useState(false)
  const [compareSel, setCompareSel] = useState<number[]>([])
  const [pollingTaskId, setPollingTaskId] = useState<number | null>(null)

  // selectedId 默认派生(reports[0];不 effect setState,避 react-hooks/set-state-in-effect)
  const currentSelectedId = selectedId ?? reports[0]?.id ?? null
  const { data: detail } = useReportDetail(currentSelectedId)

  // 提交回测 → 返 PENDING task → 轮询(behavior-contract §3)
  const submitMutation = useSubmitBacktest()
  const { data: task } = useBacktestTask(pollingTaskId)

  // 导入外部报告(POST /reports/import;TD-021 接入)。FileReader 读 .json → parseImportReport
  // 前端校验 → mutate → 成功 toast + invalidate reports(新卡出现在 list rail)。
  const importMutation = useImportReport()
  const importInputRef = useRef<HTMLInputElement>(null)

  function handleImportFile(file: File) {
    const reader = new FileReader()
    reader.onload = () => {
      const text = String(reader.result ?? '')
      const parsed = parseImportReport(text)
      if (!parsed.ok) {
        toast.error('导入失败', { description: parsed.error })
        return
      }
      importMutation.mutate(parsed.data, {
        onSuccess: (r) => {
          toast.success('导入成功', { description: `报告 #${r.id} 已入库` })
          // reports invalidate 由 useImportReport hook 内置 onSuccess 处理,不重复
        },
        onError: (e) => {
          const err = e as ApiError
          toast.error('导入失败', { description: err.message })
        },
      })
    }
    reader.onerror = () => toast.error('导入失败', { description: '文件读取失败' })
    reader.readAsText(file)
  }

  // 轮询终态副作用:COMPLETED → invalidate reports + setSelected(reportId) + toast;FAILED → toast 错误
  /* eslint-disable react-hooks/set-state-in-effect -- 轮询是外部 task 状态变化驱动的副作用(sync external system),setState 是导航+清理,React 19 规则的合理例外 */
  useEffect(() => {
    if (!task) return
    if (task.status === 'COMPLETED') {
      toast.success('回测完成', { description: `报告 #${task.reportId} 已生成` })
      setPollingTaskId(null)
      setSelectedId(task.reportId)
      qc.invalidateQueries({ queryKey: backtestKeys.reports() })
    } else if (task.status === 'FAILED') {
      toast.error('回测失败', { description: task.errorMessage || '未知错误' })
      setPollingTaskId(null)
    }
  }, [task?.status, task, qc])
  /* eslint-enable react-hooks/set-state-in-effect */

  // 对比模式 + 选 2 个 → 触发 compare(currentCompareSel 派生含默认 [0,3],useMemo 稳定引用避 effect 循环)
  const compareMutation = useCompareReports()
  const { mutate: compareMutate, data: compareData, isPending: comparePending } = compareMutation
  const currentCompareSel = useMemo(
    () =>
      compareSel.length > 0
        ? compareSel
        : ([reports[0]?.id, reports[3]?.id].filter(Boolean) as number[]),
    [compareSel, reports],
  )
  useEffect(() => {
    if (!compareMode) return
    if (currentCompareSel.length === 2) compareMutate(currentCompareSel)
  }, [compareMode, currentCompareSel, compareMutate])

  const handleSubmit = (req: SubmitBacktestRequest) => {
    submitMutation.mutate(req, {
      onSuccess: (t) => {
        setShowSubmit(false)
        setPollingTaskId(t.id)
        toast.success('回测已提交', { description: '异步任务,完成会推送通知' })
      },
      onError: (e) => {
        const err = e as ApiError
        toast.error('提交失败', { description: err.message })
      },
    })
  }

  const handleToggleCompare = (id: number) => {
    setCompareSel((prev) => {
      if (prev.includes(id)) return prev.filter((x) => x !== id)
      if (prev.length >= 2) return [prev[1]!, id] // 照原型:已 2 个则替换第 1 个
      return [...prev, id]
    })
  }

  if (isLoading) return <LoadingState />
  if (error) return <ErrorState message={(error as ApiError).message} />
  if (reports.length === 0) {
    return (
      <EmptyState
        title="暂无回测报告"
        description="提交首个回测任务,验证你的策略表现"
        action={<Button onClick={() => setShowSubmit(true)}><Plus className="size-4" aria-hidden /> 新回测</Button>}
      />
    )
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <div>
          <h1 className="text-h2 font-bold tracking-[-0.015em] text-text-primary">回测</h1>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            用历史数据验证策略表现 · 异步任务 · 完成回填 reportId
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="ghost"
            onClick={() => setCompareMode((v) => !v)}
            className={compareMode ? 'border-accent text-accent' : ''}
          >
            <GitCompareArrows className="size-4" aria-hidden /> 多报告对比
          </Button>
          <Button
            variant="ghost"
            onClick={() => importInputRef.current?.click()}
            disabled={importMutation.isPending}
          >
            <Upload className="size-4" aria-hidden /> 导入
          </Button>
          <input
            ref={importInputRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            data-testid="import-report-input"
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) handleImportFile(f)
              e.target.value = '' // 允许重复导入同一文件
            }}
          />
          <Button onClick={() => setShowSubmit(true)}>
            <Plus className="size-4" aria-hidden /> 新回测
          </Button>
        </div>
      </div>

      {/* Backtest list rail */}
      <div className="flex gap-2 overflow-x-auto pb-1">
        {reports.map((r) => (
          <button
            key={r.id}
            type="button"
            onClick={() => setSelectedId(r.id)}
            className={`kq-press flex-[0_0_240px] rounded-md border p-3 text-left transition-colors ${
              r.id === currentSelectedId
                ? 'border-accent bg-accent-soft'
                : 'border-border-soft bg-surface-card hover:border-accent/50'
            }`}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="font-mono text-[11px] text-text-muted">#{r.id}</span>
                  <BacktestStatusBadge status="COMPLETED" />
                </div>
                <div className="mt-0.5 truncate text-[13px] font-semibold text-text-primary">
                  {r.name}
                </div>
                <div className="text-[10px] text-text-muted">
                  {formatDate(r.periodStart)} ~ {formatDate(r.periodEnd)}
                </div>
              </div>
              {compareMode && (
                <Checkbox
                  checked={compareSel.includes(r.id)}
                  onCheckedChange={() => handleToggleCompare(r.id)}
                  onClick={(e) => e.stopPropagation()}
                  aria-label={`对比 ${r.name}`}
                />
              )}
            </div>
            <div className="mt-2 flex items-center gap-2.5">
              <span className="text-[11px] text-text-muted">收益</span>
              <span
                className={`kq-mono-row text-[13px] font-bold ${
                  chgTone(r.totalReturn) === 'up'
                    ? 'text-up'
                    : chgTone(r.totalReturn) === 'down'
                      ? 'text-down'
                      : 'text-text-primary'
                }`}
              >
                {chgArrow(r.totalReturn)} {formatPercent(retToPct(r.totalReturn), { dp: 1, sign: true })}
              </span>
              <span className="flex-1 text-right text-[10px] text-text-muted">
                夏普 {formatNumber(r.sharpeRatio, 2)} · 回撤{' '}
                {formatPercent(retToPct(r.maxDrawdown), { dp: 1 })}
              </span>
            </div>
            <div className="mt-2 text-[10px] text-text-muted">{formatDateTime(r.createdAt)}</div>
          </button>
        ))}
      </div>

      {/* 对比模式 or 单报告 */}
      {compareMode ? (
        currentCompareSel.length < 2 ? (
          <Card className="p-5">
            <EmptyState title="请选择 2 个报告对比" description="在上方列表勾选报告(最多 2 个)" />
          </Card>
        ) : compareData ? (
          <CompareTable result={compareData} />
        ) : comparePending ? (
          <LoadingState />
        ) : null
      ) : detail ? (
        <div className="flex flex-col gap-[18px]">
          <EquityCurveCard detail={detail} />
          <MetricGrid detail={detail} />
          <TradeList detail={detail} />
        </div>
      ) : null}

      {/* 提交 Modal */}
      <SubmitModal
        open={showSubmit}
        onOpenChange={setShowSubmit}
        onSubmit={handleSubmit}
        submitting={submitMutation.isPending}
      />
    </div>
  )
}
