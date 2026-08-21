import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { toDecimal } from '@/lib/money'
import { Chip } from '@/components/Chip'
import type { ComparisonResultDto } from '@/api/backtest'

/**
 * CompareDialog — 多报告对比视图(useCompareReports 结果展示)。
 *
 * 表格：行 = 指标，列 = 报告；每行领先项(ranking[metric][0])打 "该项领先" Chip + up 色。
 * ranking 语义(后端 ReportComparisonService):totalReturn/sharpe/winRate/profitFactor/totalTrades
 * 越大越好，maxDrawdown/avgTradeDuration 越小越好；null 指标排最后。
 */

type MetricKey = 'totalReturn' | 'sharpeRatio' | 'maxDrawdown' | 'winRate' | 'profitFactor' | 'totalTrades'

const METRIC_ROWS: { key: MetricKey; label: string; fmt: (v: number | null | undefined) => string }[] = [
  { key: 'totalReturn', label: '总收益率', fmt: (v) => fmtPct(v) },
  { key: 'sharpeRatio', label: '夏普比率', fmt: (v) => fmtNum(v) },
  { key: 'maxDrawdown', label: '最大回撤', fmt: (v) => fmtPct(v, false) },
  { key: 'winRate', label: '胜率', fmt: (v) => fmtPct(v, false) },
  { key: 'profitFactor', label: '盈亏比', fmt: (v) => fmtNum(v) },
  { key: 'totalTrades', label: '交易数', fmt: (v) => (v == null ? '—' : String(v)) },
]

function fmtPct(v: number | null | undefined, sign = true): string {
  if (v == null) return '—'
  const d = toDecimal(v).times(100)
  return `${sign && d.gte(0) ? '+' : ''}${d.toFixed(2)}%`
}
function fmtNum(v: number | null | undefined, dp = 2): string {
  return v == null ? '—' : toDecimal(v).toFixed(dp)
}

export function CompareDialog({
  open,
  onOpenChange,
  result,
  isLoading,
  error,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  result: ComparisonResultDto | null | undefined
  isLoading: boolean
  error: Error | null
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>回测对比</DialogTitle>
          <DialogDescription>各项指标按同一口径比较；收益类指标越高越好，最大回撤越小越好。</DialogDescription>
        </DialogHeader>
        {isLoading ? (
          <LoadingState rows={4} />
        ) : error ? (
          // 脱敏通用文案；关闭重开 dialog 即重新请求(父层 query 按 ids 缓存)
          <ErrorState title="对比失败" message="暂时无法加载对比结果，请关闭后重试" />
        ) : !result ? (
          <ErrorState title="无对比数据" message="请选择至少 2 个已完成的回测" />
        ) : (
          <div className="overflow-x-auto">
            <table className="kq-mono-row w-full text-body-sm">
              <thead>
                <tr className="border-b border-border text-caption text-text-muted">
                  <th className="kq-sticky-col py-xs pr-sm text-left font-medium">指标</th>
                  {result.reports.map((r) => (
                    <th key={r.id} className="py-xs pr-sm text-right font-medium">
                      <div className="text-text-primary">{r.name}</div>
                      <div className="font-normal">
                        {r.symbol} · {r.timeframe}
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {METRIC_ROWS.map((row) => {
                  const bestId = result.ranking?.[row.key]?.[0]
                  return (
                    <tr key={row.key} className="border-b border-border-soft/30">
                      <td className="kq-sticky-col py-xs pr-sm text-text-muted">{row.label}</td>
                      {result.reports.map((r) => {
                        const v = r[row.key] as number | null | undefined
                        const best = r.id === bestId
                        return (
                          <td key={r.id} className="py-xs pr-sm text-right">
                            <span className={best ? 'text-up font-semibold' : 'text-text-primary'}>
                              {row.fmt(v)}
                            </span>{' '}
                            {best && <Chip color="up" label="该项领先" size="sm" />}
                          </td>
                        )
                      })}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
