import { DataRowMono } from '@/components/DataRowMono'
import {
  formatDuration,
  formatDrawdown,
  formatPercent,
  formatRatio,
  formatWinRate,
} from '@/lib/backtestFormat'
import type { components } from '@/types/api-gen'

type MetricsDto = components['schemas']['MetricsDto']

/**
 * BacktestMetricsPanel — 回测核心指标摘要(spec §5 step 22)。
 *
 * MetricsDto 字段:totalReturn / sharpeRatio / maxDrawdown / winRate / profitFactor /
 *   totalTrades / avgTradeDurationSeconds。
 * DataRowMono 渲染(系统等宽 font-mono + tnum,DESIGN.md §data-row-mono)。
 * 涨跌色:totalReturn 正→up,负→down;maxDrawdown 始终 down(负值)。
 */
export interface BacktestMetricsPanelProps {
  metrics: MetricsDto
}

export function BacktestMetricsPanel({ metrics }: BacktestMetricsPanelProps) {
  return (
    <div className="rounded-xl bg-surface-card p-lg shadow-card">
      <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Metrics</p>
      <h3 className="mt-sm font-display text-h3">核心指标</h3>
      <dl className="mt-lg divide-y divide-border-soft">
        <DataRowMono
          label="总收益率"
          value={formatPercent(metrics.totalReturn)}
          tone={metrics.totalReturn >= 0 ? 'up' : 'down'}
        />
        <DataRowMono label="夏普比率" value={formatRatio(metrics.sharpeRatio)} />
        <DataRowMono label="最大回撤" value={formatDrawdown(metrics.maxDrawdown)} tone="down" />
        <DataRowMono label="胜率" value={formatWinRate(metrics.winRate)} />
        <DataRowMono label="盈亏比" value={formatRatio(metrics.profitFactor)} />
        <DataRowMono label="总交易笔数" value={`${metrics.totalTrades}`} />
        <DataRowMono
          label="平均持仓时长"
          value={formatDuration(metrics.avgTradeDurationSeconds)}
        />
      </dl>
    </div>
  )
}
