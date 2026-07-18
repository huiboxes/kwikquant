import { useState } from 'react'
import { Bitcoin, CalendarDays, ChevronDown, Clock, FlaskConical } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { DateRange } from 'react-day-picker'
import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { formatDate } from '@/lib/format'

interface BottomControlBarProps {
  symbol: string | undefined
  interval: string | undefined
  backtesting: boolean
  onSubmitBacktest: (range: { startTime: string; endTime: string }) => void
  /** TD-039 fork:改 symbol/interval 不更新原策略(后端无 update 端点),而是创建新策略 */
  onSymbolChange?: (symbol: string) => void
  onIntervalChange?: (interval: string) => void
}

const SYMBOLS = ['BTC/USDT', 'ETH/USDT', 'SOL/USDT', 'BNB/USDT', 'XRP/USDT']
const TIMEFRAMES = ['1m', '5m', '15m', '1h', '4h', '1d']

/** Pill-shaped 下拉选择控件(原生 select 覆盖,opacity-0)。 */
function PillSelect({
  icon: Icon,
  value,
  options,
  onChange,
}: {
  icon: LucideIcon
  value: string
  options: string[]
  onChange?: (v: string) => void
}) {
  return (
    <div className="relative flex h-[36px] cursor-pointer items-center gap-xxs rounded-pill bg-surface-3 px-sm transition-colors hover:bg-surface-hover">
      <Icon className="size-4 text-text-muted" aria-hidden />
      <span className="text-body-sm font-semibold text-text-primary">{value}</span>
      <ChevronDown className="size-3.5 text-text-muted" aria-hidden />
      <select
        className="absolute inset-0 cursor-pointer opacity-0"
        value={value}
        onChange={onChange ? (e) => onChange(e.target.value) : undefined}
        aria-label="选择"
      >
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </div>
  )
}

/**
 * BottomControlBar — 编辑器底部控制栏(照原型 workbench.html)。
 * Pill 控件:交易对 / 时间周期 / 日期范围(Popover+Calendar range) + Backtest / Run Live。
 *
 * 日期范围选好后点 Backtest → onSubmitBacktest({startTime,endTime}),父组件调 useSubmitBacktest。
 */
export function BottomControlBar({
  symbol,
  interval,
  backtesting,
  onSubmitBacktest,
  onSymbolChange,
  onIntervalChange,
}: BottomControlBarProps) {
  // 默认回测区间最近 1 年(业内常见默认:量化回测需足够样本,1 年覆盖中频周期,
  // 既不过短(噪音)也不过长(计算开销大)。TradingView/Freqtrade 等多以 1 年或全量为默认)。
  // 默认填好 → 回测按钮立即可点(优先推荐回测,非强制用户先选日期)。
  const [dateRange, setDateRange] = useState<DateRange | undefined>(() => {
    const to = new Date()
    const from = new Date()
    from.setDate(from.getDate() - 365)
    return { from, to }
  })
  const [popoverOpen, setPopoverOpen] = useState(false)

  const rangeReady = !!dateRange?.from && !!dateRange?.to
  const rangeLabel = rangeReady
    ? `${formatDate(dateRange!.from!.toISOString())} → ${formatDate(dateRange!.to!.toISOString())}`
    : '选择日期'

  const handleBacktest = () => {
    if (!dateRange?.from || !dateRange?.to) return
    onSubmitBacktest({
      startTime: dateRange.from.toISOString(),
      endTime: dateRange.to.toISOString(),
    })
  }

  return (
    <div className="flex flex-wrap items-center gap-sm bg-surface-card-2 px-base py-sm">
      {/* Symbol selector */}
      <PillSelect icon={Bitcoin} value={symbol ?? 'BTC/USDT'} options={SYMBOLS} onChange={onSymbolChange} />

      {/* Timeframe selector */}
      <PillSelect icon={Clock} value={interval ?? '1h'} options={TIMEFRAMES} onChange={onIntervalChange} />

      {/* Date range picker (Popover + Calendar range mode) */}
      <Popover open={popoverOpen} onOpenChange={setPopoverOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            className="flex h-[36px] items-center gap-xxs rounded-pill bg-surface-3 px-sm transition-colors hover:bg-surface-hover"
          >
            <CalendarDays className="size-4 text-text-muted" aria-hidden />
            <span className="kq-mono-row text-body-sm font-semibold text-text-primary">
              {rangeLabel}
            </span>
            <ChevronDown className="size-3.5 text-text-muted" aria-hidden />
          </button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="range"
            numberOfMonths={2}
            selected={dateRange}
            onSelect={setDateRange}
            disabled={{ after: new Date() }}
          />
          <div className="flex items-center justify-between border-t border-border-soft p-2">
            <span className="px-1 text-caption text-text-muted">
              {rangeReady ? rangeLabel : '请选择起止日期'}
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setPopoverOpen(false)}
              disabled={!rangeReady}
            >
              确定
            </Button>
          </div>
        </PopoverContent>
      </Popover>

      <div className="flex-1" />

      {/* Backtest button (需先选日期范围) */}
      <Button
        variant="outline"
        size="default"
        onClick={handleBacktest}
        disabled={!rangeReady || backtesting}
      >
        <FlaskConical className="size-4" aria-hidden />
        {backtesting ? '回测中…' : '回测'}
      </Button>
    </div>
  )
}
