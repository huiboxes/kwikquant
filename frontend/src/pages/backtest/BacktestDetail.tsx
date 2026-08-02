import { useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/EmptyState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { Chip } from '@/components/Chip'
import { EquityCurveChart } from '@/components/charts/EquityCurveChart'
import { useReportDetail } from '@/hooks/useBacktest'
import { toDecimal, formatMoney } from '@/lib/money'
import { buildBacktestCsv, sanitizeFileName } from './csvExport'
import { downloadEquityPng } from './pngExport'
import type { BacktestTaskDto, BacktestReportDetailDto } from '@/api/backtest'

/**
 * BacktestDetail — 选中回测的完整详情(照原型 BacktestPage.jsx 7-87 port)。
 * 权益曲线卡(不显 tab UI,只"权益曲线"标题;回撤/月度 Phase 2)+ 7 指标 grid(不渲染 sub 行;
 * 基准对比 Phase 2)+ 交易明细 + 导出 CSV/PNG 按钮。
 * 内部调 useReportDetail(reportId),reportId null 显空态+引导。
 */
export function BacktestDetail({ reportId, tasks }: { reportId: number | null; tasks: BacktestTaskDto[] }) {
  const navigate = useNavigate()
  const { data: detail, isLoading, error } = useReportDetail(reportId)
  const chartContainerRef = useRef<HTMLDivElement>(null)

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
  if (error) return <ErrorState title="加载失败" message={error.message} />
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
  const selectedTask = tasks.find((t) => t.reportId === reportId)
  const strategyName = selectedTask?.strategyName ?? 'backtest'
  const status = selectedTask?.status
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
    await downloadEquityPng(svg as unknown as SVGSVGElement, `回测-${sanitizeFileName(strategyName)}-${ts}.png`)
  }

  return (
    <div className="flex flex-col gap-sm">
      {/* 头部身份行(照原型 workbench.html:333-345) */}
      <div className="flex items-center justify-between gap-sm">
        <div className="flex items-center gap-sm">
          <h2 className="text-h2 font-semibold text-text-primary">回测报告</h2>
          <Chip
            color={status === 'COMPLETED' ? 'up' : status === 'FAILED' ? 'down' : 'neutral'}
            label={status === 'COMPLETED' ? 'Complete' : status === 'FAILED' ? 'Failed' : 'Running'}
            size="sm"
          />
          <span className="kq-mono-row text-caption text-text-muted">
            {strategyName} · {detail.symbol} · {detail.timeframe} ·{' '}
            {detail.periodStart?.slice(0, 10)} → {detail.periodEnd?.slice(0, 10)}
          </span>
        </div>
        <div className="flex gap-xxs">
          <Button variant="outline" size="sm" onClick={onExportPng}>
            导出 PNG
          </Button>
          <Button variant="outline" size="sm" onClick={onExportCsv}>
            导出 CSV
          </Button>
        </div>
      </div>

      {/* 权益曲线卡(导出按钮已迁头部;4 角标 + 关 Y 轴,照原型 workbench.html:382-394) */}
      <div className="rounded-xl bg-surface-card p-sm">
        <div className="mb-xxs text-h3 font-semibold text-text-primary">权益曲线</div>
        <div className="relative h-[280px] rounded-lg bg-surface-card-2 overflow-hidden">
          <div ref={chartContainerRef}>
            <EquityCurveChart data={curveData} height={280} width={720} color="var(--up)" showYAxis={false} />
          </div>
          <span className="kq-mono-row absolute bottom-2 left-3 text-[11px] text-text-muted">
            {detail.periodStart?.slice(0, 10)}
          </span>
          <span className="kq-mono-row absolute bottom-2 right-3 text-[11px] text-text-muted">
            {detail.periodEnd?.slice(0, 10)}
          </span>
          <span className="kq-mono-row absolute top-2 left-3 text-[11px] text-text-muted">
            ${fmtEq(detail.equityCurve?.at(-1)?.equity)}
          </span>
          <span className="kq-mono-row absolute top-2 right-3 text-[11px] text-text-muted">
            ${fmtEq(detail.equityCurve?.[0]?.equity)} (初始)
          </span>
        </div>
      </div>

      {/* 7 指标(不渲染 sub 行) */}
      <MetricGrid m={detail.metrics} />

      {/* 交易明细 */}
      <TradeList trades={detail.trades} />
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
/** 权益角标格式化(千分位 + dp=0,照原型 $11,560 无小数)。equity 是 number(api-gen EquityPointDto.equity: number)。 */
function fmtEq(v: number | undefined | null): string {
  return v == null ? '—' : formatMoney(toDecimal(v), { dp: 0 })
}
function fmtDuration(s: number | null | undefined): string {
  if (s == null) return '—'
  if (s >= 3600) return `${(s / 3600).toFixed(1)} 小时`
  return `${Math.round(s / 60)} 分钟`
}

function MetricCell({ label, value, tone }: { label: string; value: string; tone?: 'up' | 'down' }) {
  const color = tone === 'up' ? 'text-up' : tone === 'down' ? 'text-down' : 'text-text-primary'
  return (
    <div className="rounded-lg bg-surface-card-2 p-sm">
      <div className="text-caption text-text-muted mb-xxs">{label}</div>
      <div className={`kq-mono-row text-[24px] font-semibold leading-tight ${color}`}>{value}</div>
    </div>
  )
}

function MetricGrid({ m }: { m: BacktestReportDetailDto['metrics'] }) {
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
    </div>
  )
}

function TradeList({ trades }: { trades: BacktestReportDetailDto['trades'] }) {
  return (
    <div className="rounded-xl bg-surface-card p-sm">
      <div className="mb-xxs text-h3 font-semibold text-text-primary">交易明细(最近 10 笔)</div>
      <div className="overflow-x-auto">
        <table className="kq-mono-row w-full text-body-sm">
          <thead>
            <tr className="border-b border-border text-caption text-text-muted">
              <th className="py-xs pr-sm text-left font-medium">时间</th>
              <th className="py-xs pr-sm text-left font-medium">方向</th>
              <th className="py-xs pr-sm text-right font-medium">价格</th>
              <th className="py-xs pr-sm text-right font-medium">数量</th>
              <th className="py-xs pr-sm text-right font-medium">盈亏</th>
              <th className="py-xs pr-sm text-right font-medium">权益</th>
            </tr>
          </thead>
          <tbody>
            {(trades ?? []).map((t) => {
              const pnl = t.realizedPnl != null ? toDecimal(t.realizedPnl) : null
              const pnlTone = pnl == null ? 'neutral' : pnl.gte(0) ? 'up' : 'down'
              const pnlText = pnl == null ? '—' : `${pnl.gte(0) ? '+' : ''}${pnl.toFixed(2)}`
              return (
                <tr key={t.id} className="border-b border-border-soft/30">
                  <td className="py-xs pr-sm text-text-muted">{t.time?.slice(0, 19)}</td>
                  <td className={`py-xs pr-sm uppercase ${t.side === 'buy' ? 'text-up' : 'text-down'}`}>
                    {t.side}
                  </td>
                  <td className="py-xs pr-sm text-right text-text-primary">{t.price}</td>
                  <td className="py-xs pr-sm text-right text-text-primary">{t.amount}</td>
                  <td className={`py-xs pr-sm text-right ${pnlTone === 'up' ? 'text-up' : pnlTone === 'down' ? 'text-down' : 'text-text-muted'}`}>
                    {pnlText}
                  </td>
                  <td className="py-xs pr-sm text-right text-text-primary">{t.equity}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
