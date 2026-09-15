import { useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/EmptyState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { Chip } from '@/components/Chip'
import { Download, Sparkles } from 'lucide-react'
import { EquityCurveChart } from '@/components/charts/EquityCurveChart'
import { useReportDetail } from '@/hooks/useBacktest'
import { toDecimal, formatMoney } from '@/lib/money'
import { equityColor } from '@/lib/equity'
import { buildBacktestCsv, sanitizeFileName } from './csvExport'
import { downloadEquityPng } from './pngExport'
import { EFFECT_LABELS, effectLabel } from './positionEffect'
import { exportReport } from '@/api/backtest'
import type { BacktestTaskDto, BacktestReportDetailDto } from '@/api/backtest'

function backtestFailureMessage(task: {
  errorMessage?: string | null
  userMessage?: string | null
}): string {
  // 后端按失败分类映射的产品文案优先(替代裸 stderr 透传)
  if (task.userMessage) return task.userMessage
  const fallback = '回测执行失败，请检查策略代码或调整参数后重试'
  const message = task.errorMessage
  if (!message) return fallback
  if (/\n|traceback|exception|error:|stderr|subprocess|exit code|java\./i.test(message)) return fallback
  return message
}

/**
 * BacktestDetail — 选中回测的完整详情。
 * 权益曲线卡(不显 tab UI，只"权益曲线"标题；回撤/月度 Phase 2)+ 7 指标 grid(不渲染 sub 行；
 * 基准对比 Phase 2)+ 交易明细 + 导出 CSV/PNG 按钮。
 * 内部调 useReportDetail(reportId),reportId null 显空态+引导。
 */
export function BacktestDetail({
  reportId,
  selectedTaskId,
  tasks,
}: {
  reportId: number | null
  selectedTaskId: number | null
  tasks: BacktestTaskDto[]
}) {
  const navigate = useNavigate()
  const { data: detail, isLoading, error, refetch } = useReportDetail(reportId)
  const chartContainerRef = useRef<HTMLDivElement>(null)
  const selectedTask =
    tasks.find((task) => task.id === selectedTaskId) ?? tasks.find((task) => task.reportId === reportId)

  if (selectedTask?.status === 'FAILED') {
    return (
      <div className="flex flex-col items-center gap-sm rounded-xl border border-border-soft bg-surface-card p-xl text-center">
        <div className="flex size-12 items-center justify-center rounded-full bg-down/10">
          <svg className="size-6 text-down" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
            <path d="M12 9v4M12 17h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>
        <h3 className="text-h3 font-semibold text-text-primary">回测失败</h3>
        <p className="max-w-sm text-body-sm text-text-muted">
          {backtestFailureMessage(selectedTask)}
        </p>
        <Button variant="default" onClick={() => navigate(`/strategy?taskId=${selectedTask.id}&retry=1`)}>
          重新发起回测
        </Button>
      </div>
    )
  }
  if (reportId == null) {
    const anyRunning = tasks.some((t) => t.status === 'RUNNING' || t.status === 'PENDING')
    return (
      <EmptyState
        title={anyRunning ? '回测完成后将显示结果' : '选择一个回测查看详情'}
        description="列表中选择一个已完成的回测"
        action={<Button onClick={() => navigate('/strategy')}>去策略页发起新回测</Button>}
      />
    )
  }
  if (isLoading) return <LoadingState rows={5} />
  {/* 与其余页面一致：脱敏通用文案 + 重试按钮，不透出原始 error.message */}
  if (error) {
    return <ErrorState title="加载失败" message="暂时无法加载回测报告，请稍后重试" onRetry={() => refetch()} />
  }
  if (!detail) {
    return (
      <EmptyState
        title="回测不存在"
        description="该回测可能已删除"
        action={<Button onClick={() => navigate('/strategy')}>去策略页发起新回测</Button>}
      />
    )
  }

  const curveData = (detail.equityCurve ?? []).map((p, i) => [i, p.equity] as [number, number])
  const strategyName = selectedTask?.strategyName ?? 'backtest'
  const status = selectedTask?.status
  const reproducibility = parseReproducibility(detail.params)
  const ts = new Date().toISOString().slice(0, 16).replace(/[-T:]/g, '')

  const onExportCsv = () => {
    const csv = buildBacktestCsv(detail, strategyName)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `回测-${sanitizeFileName(strategyName)}-${ts}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }
  const onExportPng = async () => {
    const svg = chartContainerRef.current?.querySelector('svg')
    if (!svg) return
    const m = detail.metrics
    const ret = m?.totalReturn != null ? toDecimal(m.totalReturn) : null
    const retTone: 'up' | 'down' | 'neutral' = ret == null ? 'neutral' : ret.gte(0) ? 'up' : 'down'
    const retText = ret == null ? '—' : `${ret.gte(0) ? '+' : ''}${ret.times(100).toFixed(2)}%`
    await downloadEquityPng(svg as unknown as SVGSVGElement, `回测-${sanitizeFileName(strategyName)}-${ts}.png`, {
      strategyName,
      symbol: detail.symbol,
      interval: detail.timeframe,
      range: `${detail.periodStart?.slice(0, 10)} → ${detail.periodEnd?.slice(0, 10)}`,
      totalReturn: retText,
      totalReturnTone: retTone,
      // PERP 口径随图走(与 CSV 导出同纪律:导出物脱离 UI 也不丢近似声明)
      marketNote:
        detail.marketType === 'PERP' ? '永续合约 · 强平为 bar 极值近似 · 胜率/盈亏比为毛配对口径' : undefined,
    })
  }
  // 导出 JSON(GET /reports/{id}/export):格式 = import 消费格式，下载物可直接再导入(迁移闭环)
  const onExportJson = async () => {
    if (reportId == null) return
    try {
      const { blob, filename } = await exportReport(reportId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename ?? `backtest-report-${reportId}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      // 后端带真实原因(如"PERP backtest report export is not supported yet")时透出;
      // 状态码兜底文案才提示重试(重试对确定性拒绝无意义)
      const msg = e instanceof Error ? e.message : ''
      toast.error(msg && !msg.startsWith('export failed:') ? `导出失败：${msg}` : '导出失败，请重试')
    }
  }

  return (
    <div className="flex flex-col gap-sm">
      {/* 头部身份行;flex-wrap 让导出按钮组移动端换行，防定宽按钮撑破 */}
      <div className="flex flex-wrap items-center justify-between gap-sm">
        <div className="flex min-w-0 flex-wrap items-center gap-sm">
          <h2 className="text-h2 font-semibold text-text-primary">回测报告</h2>
          <Chip
            color={status === 'COMPLETED' ? 'up' : 'neutral'}
            label={status === 'COMPLETED' ? '已完成' : '进行中'}
            size="sm"
          />
          {/* PERP badge(live-paper-badge 同款 token:accent-soft 底 + accent-warm 字) */}
          {detail.marketType === 'PERP' && (
            <span
              className="rounded-pill bg-accent-soft px-xs py-[1px] text-caption font-semibold text-accent-warm"
              title="永续合约回测(净持仓账本,支持做空与杠杆)"
            >
              合约
            </span>
          )}
          {/* 组合报告:逗号拼接串过长,头部行显汇总标签,完整清单进 title(与 BacktestRail 同口径) */}
          <span
            className="kq-mono-row text-caption text-text-muted"
            title={detail.symbols?.length ? detail.symbols.join(', ') : undefined}
          >
            {strategyName} ·{' '}
            {detail.symbols?.length ? `组合·${detail.symbols.length} 标的` : detail.symbol} ·{' '}
            {detail.timeframe} · {detail.periodStart?.slice(0, 10)} → {detail.periodEnd?.slice(0, 10)}
          </span>
        </div>
        <div className="flex gap-xxs">
          {/* AI 解读(P1):深链回策略页会话 tab 自动发问，携带 reportId + strategyId
              (strategyId 供策略页选中门控，报告解读落入该策略的 AI 会话)。
              无关联任务(纯 reportId 直达)时不渲染——解读会话必须归属策略。 */}
          {selectedTask?.strategyId != null && (
            <Button
              size="sm"
              onClick={() =>
                navigate(
                  `/strategy?strategyId=${selectedTask.strategyId}&reportId=${reportId}&ai=1`,
                )
              }
              title="用 AI 解读这次回测的指标、回撤与改进方向"
            >
              <Sparkles aria-hidden /> AI 解读
            </Button>
          )}
          {/* 导出 JSON = import 闭环格式,SPOT-only:PERP 报告后端确定性拒(422,导出契约不承载
              position_effect/liquidation,再导入必按 SPOT 语义错配)——按钮直接不渲染,
              不留"点击必失败还提示重试"的死路 */}
          {detail.marketType !== 'PERP' && (
            <Button variant="outline" size="sm" onClick={onExportJson}>
              <Download /> 导出 JSON
            </Button>
          )}
          <Button variant="outline" size="sm" onClick={onExportPng}>
            <Download /> 导出 PNG
          </Button>
          <Button variant="outline" size="sm" onClick={onExportCsv}>
            <Download /> 导出 CSV
          </Button>
        </div>
      </div>

      {/* PERP 强平近似声明(spec §4.2 强制透出,防研究侧误读保守偏差) */}
      {detail.marketType === 'PERP' && detail.liquidationModel != null && (
        <div className="rounded-lg bg-warning-bg p-xs text-body-sm text-warning-text">
          <span className="font-semibold">近似声明：</span>
          强平判定为 {detail.liquidationModel === 'BAR_EXTREME_APPROX' ? 'bar 极值近似' : detail.liquidationModel}
          ——按 bar 内极值价评估保证金击穿，较交易所真实强平存在保守偏差；胜率/盈亏比为毛配对口径，不含资金费与未实现盈亏。
        </div>
      )}

      {/* 权益曲线卡(导出按钮在头部；4 角标 + 关 Y 轴) */}
      <div className="rounded-xl bg-surface-card p-sm">
        <div className="mb-xxs text-h3 font-semibold text-text-primary">权益曲线</div>
        <div className="relative h-[280px] rounded-lg bg-surface-card-2 overflow-hidden">
          <div ref={chartContainerRef}>
            {/* 线色按收益方向取涨跌语义，亏损曲线不涂涨绿 */}
            <EquityCurveChart data={curveData} height={280} width={720} color={equityColor(curveData)} showYAxis={false} />
          </div>
          <span className="kq-mono-row absolute bottom-2 left-3 text-caption-sm text-text-muted">
            {detail.periodStart?.slice(0, 10)}
          </span>
          <span className="kq-mono-row absolute bottom-2 right-3 text-caption-sm text-text-muted">
            {detail.periodEnd?.slice(0, 10)}
          </span>
          <span className="kq-mono-row absolute top-2 left-3 text-caption-sm text-text-muted">
            ${fmtEq(detail.equityCurve?.at(-1)?.equity)}
          </span>
          <span className="kq-mono-row absolute top-2 right-3 text-caption-sm text-text-muted">
            ${fmtEq(detail.equityCurve?.[0]?.equity)} (初始)
          </span>
        </div>
      </div>

      {/* 7 指标(不渲染 sub 行);PERP 追加累计资金费 cell(8 格整两行) */}
      <MetricGrid m={detail.metrics} fundingCum={detail.marketType === 'PERP' ? detail.equityCurve?.at(-1)?.fundingCum : null} />

      {reproducibility && <ReproducibilitySnapshot value={reproducibility} />}

      {/* 交易明细 */}
      <TradeList trades={detail.trades} perp={detail.marketType === 'PERP'} />
    </div>
  )
}

type Reproducibility = {
  strategyCodeHash?: string
  data?: {
    requestedStart?: string
    requestedEnd?: string
    actualStart?: string
    actualEnd?: string
    bars?: number
    version?: string
    /** PERP 资金费序列版本哈希与期数(worker reproducibility,SPOT 报告缺省) */
    fundingVersion?: string
    /** fundingVersion 哈希的行形态字段名列表(schema 在 hash 输入内,自描述,spec §7) */
    fundingSchema?: string[]
    fundingPeriods?: number
  }
  matching?: Record<string, unknown>
  execution?: { engineVersion?: string; orderFillTiming?: string }
  warnings?: string[]
}

function parseReproducibility(params: string | null | undefined): Reproducibility | null {
  if (!params) return null
  try {
    const parsed = JSON.parse(params) as { _kwikquant?: Reproducibility }
    return parsed._kwikquant ?? null
  } catch {
    return null
  }
}

function ReproducibilitySnapshot({ value }: { value: Reproducibility }) {
  const warnings = value.warnings ?? []
  return (
    <div className="rounded-xl bg-surface-card p-sm">
      <div className="mb-xs flex items-center justify-between gap-sm">
        <h3 className="text-h3 font-semibold text-text-primary">可复现快照</h3>
        <Chip color={warnings.length > 0 ? 'warning' : 'up'} label={warnings.length > 0 ? '存在提示' : '无已知警告'} size="sm" />
      </div>
      {warnings.length > 0 && (
        <div className="mb-sm rounded-lg bg-warning-bg p-xs text-body-sm text-warning-text">
          <div className="mb-xxs font-semibold">可信度提示</div>
          {warnings.map((warning) => <div key={warning}>{warning}</div>)}
        </div>
      )}
      <div className="grid gap-xs text-body-sm md:grid-cols-2">
        <SnapshotRow label="策略代码哈希" value={value.strategyCodeHash} />
        <SnapshotRow label="数据版本" value={value.data?.version} />
        <SnapshotRow label="请求区间" value={`${value.data?.requestedStart ?? '—'} → ${value.data?.requestedEnd ?? '—'}`} />
        <SnapshotRow label="实际区间 / K线数" value={`${value.data?.actualStart ?? '—'} → ${value.data?.actualEnd ?? '—'} · ${value.data?.bars ?? '—'}`} />
        <SnapshotRow label="订单成交时点" value={value.execution?.orderFillTiming} />
        <SnapshotRow label="撮合配置" value={value.matching ? JSON.stringify(value.matching) : undefined} />
        {value.data?.fundingVersion != null && (
          <SnapshotRow
            label="资金费数据"
            value={`${value.data.fundingVersion}${value.data.fundingPeriods != null ? ` · ${value.data.fundingPeriods} 期` : ''}`}
          />
        )}
      </div>
    </div>
  )
}

function SnapshotRow({ label, value }: { label: string; value?: string }) {
  return (
    <div className="rounded-lg bg-surface-card-2 p-xs">
      <div className="text-caption text-text-muted">{label}</div>
      <div className="kq-mono-row break-all text-caption text-text-primary">{value ?? '—'}</div>
    </div>
  )
}

function fmtPct(v: number | null | undefined, sign = true): string {
  if (v == null) return '—'
  const d = toDecimal(v).times(100)
  return `${sign && d.gte(0) ? '+' : ''}${d.toFixed(2)}%`
}
function fmtNum(v: number | null | undefined, dp = 2): string {
  return v == null ? '—' : toDecimal(v).toFixed(dp)
}
/** 权益角标格式化(千分位，不留小数，dp=0)。equity 是 number(api-gen EquityPointDto.equity: number)。 */
function fmtEq(v: number | undefined | null): string {
  return v == null ? '—' : formatMoney(toDecimal(v), { dp: 0 })
}
function fmtDuration(s: number | null | undefined): string {
  if (s == null) return '—'
  if (s >= 3600) return `${(s / 3600).toFixed(1)} 小时`
  return `${Math.round(s / 60)} 分钟`
}

function MetricCell({
  label,
  value,
  tone,
  title,
}: {
  label: string
  value: string
  tone?: 'up' | 'down'
  title?: string
}) {
  const color = tone === 'up' ? 'text-up' : tone === 'down' ? 'text-down' : 'text-text-primary'
  return (
    <div className="rounded-lg bg-surface-card-2 p-sm" title={title}>
      <div className="text-caption text-text-muted mb-xxs">{label}</div>
      <div className={`kq-mono-row text-metric font-semibold leading-tight ${color}`}>{value}</div>
    </div>
  )
}

/** 累计资金费(PERP 专属 cell):带符号金额,收入 up / 支出 down;null(SPOT 或无曲线)不渲染。 */
function fmtFunding(v: number | null | undefined): string {
  if (v == null) return '—'
  return formatMoney(toDecimal(v), { dp: 2, sign: true })
}

function MetricGrid({
  m,
  fundingCum,
}: {
  m: BacktestReportDetailDto['metrics']
  /** PERP 报告末点累计资金费(净收入,可为负);SPOT 传 null 不渲染该 cell */
  fundingCum?: number | null
}) {
  const fundingTone =
    fundingCum == null ? undefined : toDecimal(fundingCum).gte(0) ? 'up' : 'down'
  return (
    <div className="grid grid-cols-4 gap-sm">
      <MetricCell
        label="总收益率"
        value={fmtPct(m?.totalReturn)}
        tone={m?.totalReturn != null && toDecimal(m.totalReturn).gte(0) ? 'up' : 'down'}
      />
      <MetricCell label="夏普比率" value={fmtNum(m?.sharpeRatio)} />
      <MetricCell label="最大回撤" value={fmtPct(m?.maxDrawdown, false)} tone="down" />
      <MetricCell label="胜率" value={fmtPct(m?.winRate, false)} />
      <MetricCell label="盈亏比" value={fmtNum(m?.profitFactor)} />
      <MetricCell label="交易数" value={m?.totalTrades != null ? String(m.totalTrades) : '—'} />
      <MetricCell label="平均持仓时长" value={fmtDuration(m?.avgTradeDurationSeconds)} />
      {fundingCum != null && (
        <MetricCell
          label="累计资金费"
          value={fmtFunding(fundingCum)}
          tone={fundingTone}
          title="持仓期间资金费净收支：正=收取，负=支出（已计入权益曲线，不在胜率/盈亏比毛配对口径内）"
        />
      )}
    </div>
  )
}

function TradeList({ trades, perp }: { trades: BacktestReportDetailDto['trades']; perp: boolean }) {
  return (
    <div className="rounded-xl bg-surface-card p-sm">
      <div className="mb-xxs text-h3 font-semibold text-text-primary">交易明细(最近 10 笔)</div>
      <div className="overflow-x-auto">
        <table className="kq-mono-row w-full text-body-sm">
          <thead>
            <tr className="border-b border-border text-caption text-text-muted">
              <th className="kq-sticky-col py-xs pr-sm text-left font-medium">时间</th>
              <th className="py-xs pr-sm text-left font-medium">{perp ? '持仓意图' : '方向'}</th>
              <th className="py-xs pr-sm text-right font-medium">价格</th>
              <th
                className="py-xs pr-sm text-right font-medium"
                title={perp ? '币数量（base coin，非合约张数——平台全链路币口径）' : undefined}
              >
                {perp ? '数量(币)' : '数量'}
              </th>
              <th className="py-xs pr-sm text-right font-medium">盈亏</th>
              <th
                className="py-xs pr-sm text-right font-medium"
                title={
                  perp
                    ? 'PERP 逐笔权益不输出：逐笔权益含未实现与资金费，与毛配对盈亏并排会背离误读，账户口径见权益曲线'
                    : undefined
                }
              >
                权益
              </th>
            </tr>
          </thead>
          <tbody>
            {(trades ?? []).map((t) => {
              const pnl = t.realizedPnl != null ? toDecimal(t.realizedPnl) : null
              const pnlTone = pnl == null ? 'neutral' : pnl.gte(0) ? 'up' : 'down'
              const pnlText = pnl == null ? '—' : `${pnl.gte(0) ? '+' : ''}${pnl.toFixed(2)}`
              // PERP 方向列显四向中文(long 绿/short 红,同 side 语义色先例);side 是派生量,
              // buy≠开仓,不能按 side 渲染(DESIGN.md:枚举不作主文案,title 辅助)
              const effect = perp ? EFFECT_LABELS[t.positionEffect ?? ''] : undefined
              // effect 未知(防御边界)走 muted 不默认绿
              const effectTone = effect == null ? 'text-text-muted' : effect.long === false ? 'text-down' : 'text-up'
              return (
                <tr key={t.id} className="border-b border-border-soft/30">
                  <td className="kq-sticky-col py-xs pr-sm text-text-muted">{t.time?.slice(0, 19)}</td>
                  {perp ? (
                    <td className={`py-xs pr-sm ${effectTone}`}>
                      <span title={t.positionEffect ?? undefined}>{effectLabel(t.positionEffect)}</span>
                      {t.liquidation && (
                        <span
                          className="ml-xxs rounded-pill bg-warning-bg px-xxs py-[1px] text-caption-sm font-medium text-warning-text"
                          title="bar 极值近似强平成交"
                        >
                          强平
                        </span>
                      )}
                    </td>
                  ) : (
                    <td className={`py-xs pr-sm ${t.side === 'buy' ? 'text-up' : 'text-down'}`}>
                      {t.side === 'buy' ? '买入' : '卖出'}
                    </td>
                  )}
                  <td className="py-xs pr-sm text-right text-text-primary">{t.price}</td>
                  <td className="py-xs pr-sm text-right text-text-primary">{t.amount}</td>
                  <td className={`py-xs pr-sm text-right ${pnlTone === 'up' ? 'text-up' : pnlTone === 'down' ? 'text-down' : 'text-text-muted'}`}>
                    {pnlText}
                  </td>
                  {/* PERP 报告逐笔累计权益恒 null(trade 口径不含未实现/资金费,防与曲线背离误读) */}
                  <td className="py-xs pr-sm text-right text-text-primary">{t.equity ?? '—'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
