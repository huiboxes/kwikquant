import { useState } from 'react'
import { Bitcoin, CalendarDays, ChevronDown, Clock, FlaskConical, Landmark, Save } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { DateRange } from 'react-day-picker'
import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { formatDate } from '@/lib/format'

export interface BacktestRange {
  startTime: string
  endTime: string
  exchange: string
  symbol: string
  interval: string
}

interface BottomControlBarProps {
  /** 当前回测用 symbol/interval(controlled by 父,与策略可不同)。 */
  symbol: string
  interval: string
  /** 策略本身的 symbol/interval(用于检测"与策略不同"显示非阻塞提示)。 */
  strategySymbol: string | undefined
  strategyInterval: string | undefined
  /** 回测交易所(从父组件账户选择取,默认 'OKX' 项目基准;用户可改选跨交易所)。 */
  exchange: string
  backtesting: boolean
  onSubmitBacktest: (range: BacktestRange) => void
  /** symbol/interval 改选 → 父 setState(就地覆盖回测参数,不再阻塞式 fork)。 */
  onSymbolChange?: (symbol: string) => void
  onIntervalChange?: (interval: string) => void
  onExchangeChange?: (exchange: string) => void
  /** 显式"另存为新策略"(非阻塞:用户主动点才 fork,回测不受影响)。 */
  onSaveAsNewStrategy?: () => void
}

const SYMBOLS = ['BTC/USDT', 'ETH/USDT', 'SOL/USDT', 'BNB/USDT', 'XRP/USDT']
const TIMEFRAMES = ['1m', '5m', '15m', '1h', '4h', '1d']
const EXCHANGES = ['OKX', 'BINANCE', 'BITGET']

/** 预设区间(快速选回测样本,免手挑日期之苦)。 */
const RANGE_PRESETS: { label: string; days: number }[] = [
  { label: '近 1 月', days: 30 },
  { label: '近 3 月', days: 90 },
  { label: '近 6 月', days: 180 },
  { label: '近 1 年', days: 365 },
]

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
        {...(onChange
          ? { value, onChange: (e) => onChange(e.target.value) }
          : { defaultValue: value, disabled: true })}
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
 * Pill 控件:交易所 / 交易对 / 时间周期 / 日期范围(Popover+Calendar range) + 回测。
 *
 * 交互改造(2026-07-24,用户要求非阻塞):改 symbol/interval 不再弹"创建新策略"阻塞式
 * ConfirmDialog,而是就地覆盖回测参数(回测按钮立即可点)。与策略不同时显示非阻塞内联提示 +
 * "另存为新策略"显式按钮(用户主动点才 fork,不挡回测)。
 *
 * exchange 由父组件(StrategyPage)从当前账户取后传入(回测数据获取重构:exchange 不再用
 * 策略字段 selected.exchange — 模拟盘 OKX 账户查 Binance klines 0 行的根因)。点回测 →
 * onSubmitBacktest({startTime, endTime, exchange, symbol, interval})。
 */
export function BottomControlBar({
  symbol,
  interval,
  strategySymbol,
  strategyInterval,
  exchange,
  backtesting,
  onSubmitBacktest,
  onSymbolChange,
  onIntervalChange,
  onExchangeChange,
  onSaveAsNewStrategy,
}: BottomControlBarProps) {
  // 默认回测区间最近 1 年(量化回测需足够样本,1 年覆盖中频周期;既不过短(噪音)也不过长(计算开销大))。
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

  // symbol/interval 与策略不同 → 非阻塞提示(就地回测,另存为显式操作)
  const differsFromStrategy =
    (!!strategySymbol && symbol !== strategySymbol) ||
    (!!strategyInterval && interval !== strategyInterval)

  const handlePreset = (days: number) => {
    const to = new Date()
    const from = new Date()
    from.setDate(from.getDate() - days)
    setDateRange({ from, to })
  }

  const handleBacktest = () => {
    if (!dateRange?.from || !dateRange?.to) return
    onSubmitBacktest({
      startTime: dateRange.from.toISOString(),
      endTime: dateRange.to.toISOString(),
      exchange,
      symbol,
      interval,
    })
  }

  return (
    <div className="flex flex-wrap items-center gap-sm bg-surface-card-2 px-base py-sm">
      {/* Exchange selector(父传账户 exchange,可跨交易所改选) */}
      <PillSelect icon={Landmark} value={exchange} options={EXCHANGES} onChange={onExchangeChange} />

      {/* Symbol selector(就地覆盖回测 symbol,不再阻塞 fork) */}
      <PillSelect icon={Bitcoin} value={symbol} options={SYMBOLS} onChange={onSymbolChange} />

      {/* Timeframe selector(就地覆盖回测 interval) */}
      <PillSelect icon={Clock} value={interval} options={TIMEFRAMES} onChange={onIntervalChange} />

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
          {/* 预设区间快捷按钮(免手挑,直击"时间选择不正常"痛点) */}
          <div className="flex flex-wrap gap-xxs border-b border-border-soft p-2">
            {RANGE_PRESETS.map((p) => (
              <button
                key={p.label}
                type="button"
                onClick={() => handlePreset(p.days)}
                className="rounded-pill bg-surface-3 px-sm py-xxs text-caption text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary"
              >
                {p.label}
              </button>
            ))}
          </div>
          <Calendar
            mode="range"
            numberOfMonths={1}
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

      {/* 非阻塞"与策略不同"提示 + 显式另存为(不挡回测) */}
      {differsFromStrategy && onSaveAsNewStrategy && (
        <div className="flex items-center gap-xxs rounded-pill border border-warning-soft bg-warning-soft/40 px-sm py-xxs">
          <span className="text-caption text-text-secondary">
            已用 {symbol} · {interval} 回测(与策略不同)
          </span>
          <button
            type="button"
            onClick={onSaveAsNewStrategy}
            className="flex items-center gap-xxs rounded-pill bg-surface-card px-xs py-[2px] text-caption font-medium text-text-primary transition-colors hover:bg-surface-hover"
            title="以当前 symbol/interval 创建新策略,原策略不变"
          >
            <Save className="size-3" aria-hidden />
            另存为新策略
          </button>
        </div>
      )}

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
