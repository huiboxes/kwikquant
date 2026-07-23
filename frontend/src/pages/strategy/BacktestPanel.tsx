import { useNavigate } from 'react-router-dom'
import { ExternalLink } from 'lucide-react'
import { Chip } from '@/components/Chip'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/EmptyState'
import { ErrorState } from '@/components/ErrorState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { EquityCurveChart } from '@/components/charts/EquityCurveChart'
import { useReports, useReportDetail } from '@/hooks/useBacktest'
import { toDecimal } from '@/lib/money'

// TODO: 后端 reports 补 strategyId 后,加回 strategyId prop 按策略过滤报告

// metrics 是 BigDecimal 序列化的 JSON number(后端 write-bigdecimal-as-plain 缺口,money.ts 注释),
// 用 decimal.js 格式化 + 防 null(Java calculateSharpeRatio 可能返 null,见 task 51 sharpe_ratio NULL)
const fmtMetric = (v: number | null | undefined, dp = 2): string =>
  v == null ? '—' : toDecimal(v).toFixed(dp)
const fmtPct = (v: number | null | undefined, opts?: { sign?: boolean }): string => {
  if (v == null) return '—'
  const d = toDecimal(v).times(100)
  return `${opts?.sign && d.gte(0) ? '+' : ''}${d.toFixed(2)}%`
}

/** 将秒数转为可读时长 */
function fmtDuration(seconds: number): string {
  if (seconds >= 3600) return `${(seconds / 3600).toFixed(1)}h`
  return `${Math.round(seconds / 60)}m`
}

/** 单个指标单元格 */
function MetricCell({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone?: 'up' | 'down'
}) {
  const colorClass = tone === 'up' ? 'text-up' : tone === 'down' ? 'text-down' : 'text-text-primary'
  return (
    <div className="rounded-md bg-surface-card-2 p-xs text-center">
      <div className="mb-[2px] text-caption text-text-muted">{label}</div>
      <div className={`kq-mono-row text-h2 font-semibold ${colorClass}`}>{value}</div>
    </div>
  )
}

/** 详细指标行 */
function DataRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <span className="text-caption text-text-muted">{label}</span>
      <span className="kq-mono-row text-body-sm text-text-primary">{value}</span>
    </div>
  )
}

/**
 * BacktestPanel — 右侧 340px 回测结果面板(照原型 workbench.html Right Panel)。
 * 数据源:useReports → useReportDetail → MetricsDto + EquityPointDto[]。
 */
export function BacktestPanel() {
  const navigate = useNavigate()
  const { data: reports, isLoading: listLoading, error: listError } = useReports({ page: 1, pageSize: 5 })

  // 取最新一条报告
  const latestReport = reports?.content?.[0] ?? null
  const reportId = latestReport?.id ?? null

  const {
    data: detail,
    isLoading: detailLoading,
    error: detailError,
  } = useReportDetail(reportId)

  if (listError || detailError) {
    return (
      <div className="hidden w-[340px] shrink-0 flex-col overflow-hidden max-[1100px]:hidden lg:flex">
        <div className="m-xxs flex-1 rounded-xl bg-surface-card p-sm">
          <ErrorState title="加载失败" message={(listError ?? detailError)?.message} />
        </div>
      </div>
    )
  }

  if (listLoading || detailLoading) {
    return (
      <div className="hidden w-[340px] shrink-0 flex-col overflow-hidden lg:flex">
        <div className="m-xxs flex-1 rounded-xl bg-surface-card p-sm">
          <LoadingState rows={5} />
        </div>
      </div>
    )
  }

  if (!detail || !detail.metrics) {
    return (
      <div className="hidden w-[340px] shrink-0 flex-col overflow-hidden lg:flex">
        <div className="m-xxs flex-1 rounded-xl bg-surface-card p-sm">
          <EmptyState title="暂无回测结果" description="运行回测后结果将显示在这里" />
        </div>
      </div>
    )
  }

  const m = detail.metrics
  const curveData = (detail.equityCurve ?? []).map((p, i) => [i, p.equity] as [number, number])

  return (
    <div className="hidden w-[340px] shrink-0 flex-col overflow-hidden lg:flex">
      <div className="m-xxs flex flex-1 flex-col overflow-hidden rounded-xl bg-surface-card">
        {/* Header */}
        <div className="flex items-center justify-between px-sm py-xs">
          <div className="flex items-center gap-xxs">
            <span className="text-h3 font-semibold text-text-primary">回测结果</span>
            <Chip color="up" label="Complete" size="sm" />
          </div>
          <button
            type="button"
            onClick={() => navigate('/backtest')}
            className="text-text-muted transition-colors hover:text-text-primary"
            title="查看详情"
          >
            <ExternalLink className="size-[18px]" aria-hidden />
          </button>
        </div>

        {/* 3-col metrics */}
        <div className="grid grid-cols-3 gap-xxs px-sm">
          <MetricCell
            label="总收益"
            value={fmtPct(m.totalReturn, { sign: true })}
            tone={m.totalReturn != null && m.totalReturn >= 0 ? 'up' : 'down'}
          />
          <MetricCell label="夏普" value={fmtMetric(m.sharpeRatio)} />
          <MetricCell
            label="回撤"
            value={fmtPct(m.maxDrawdown, { sign: true })}
            tone="down"
          />
        </div>

        {/* Equity curve */}
        <div className="px-sm py-xs">
          <div className="mb-xxs text-caption text-text-muted">权益曲线</div>
          <EquityCurveChart data={curveData} height={140} color="var(--up)" />
        </div>

        {/* Detail metrics 2-col */}
        <div className="px-sm pb-xs">
          <div className="grid grid-cols-2 gap-x-sm gap-y-xxs rounded-md bg-surface-card-2 px-sm py-xs">
            <DataRow label="胜率" value={fmtPct(m.winRate)} />
            <DataRow label="盈亏比" value={fmtMetric(m.profitFactor)} />
            <DataRow label="交易数" value={String(m.totalTrades)} />
            <DataRow label="持仓" value={fmtDuration(m.avgTradeDurationSeconds)} />
          </div>
        </div>

        {/* Detail button */}
        <div className="px-sm pb-sm">
          <Button
            variant="ghost"
            className="w-full"
            onClick={() => navigate('/backtest')}
          >
            查看详情
          </Button>
        </div>
      </div>
    </div>
  )
}
