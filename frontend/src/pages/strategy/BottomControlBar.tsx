import { useState } from 'react'
import { Bitcoin, Clock, FlaskConical, Landmark, Save } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { DateRange } from 'react-day-picker'
import { Button } from '@/components/ui/button'
import { DateRangePicker } from '@/components/ui/date-range-picker'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { usePairs } from '@/hooks/useMarket'

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
  /** 策略本身的 symbol/interval/exchange(用于检测"与策略不同"显示非阻塞提示)。 */
  strategySymbol: string | undefined
  strategyInterval: string | undefined
  strategyExchange: string | undefined
  /** 回测交易所(父组件从 uiStore 取,默认 'OKX' 项目基准;用户可改选跨交易所)。 */
  exchange: string
  /** 策略市场类型(SPOT/PERP),用于 usePairs 拉对应交易对;空策略 fallback SPOT。 */
  marketType?: string
  backtesting: boolean
  onSubmitBacktest: (range: BacktestRange) => void
  /** symbol/interval/exchange 改选 → 父 setState(就地覆盖回测参数,不再阻塞式 fork)。 */
  onSymbolChange?: (symbol: string) => void
  onIntervalChange?: (interval: string) => void
  onExchangeChange?: (exchange: string) => void
  /** 显式"另存为新策略"(非阻塞:用户主动点才 fork,回测不受影响)。 */
  onSaveAsNewStrategy?: () => void
}

const SYMBOLS = ['BTC/USDT', 'ETH/USDT', 'SOL/USDT', 'BNB/USDT', 'XRP/USDT']
const TIMEFRAMES = ['1m', '5m', '15m', '1h', '4h', '1d']
const EXCHANGES = ['OKX', 'BINANCE', 'BITGET']

/**
 * Pill-shaped 下拉选择控件(shadcn Select,SelectTrigger 注入 Pill 外观)。
 *
 * 替换原"原生 <select> + opacity-0 浮层"实现(只换皮不换骨,下拉浮层走浏览器原生 UI
 * 与下单组件不一致)。现在用 components/ui/select.tsx 的 shadcn Select,trigger +
 * 浮层都走 DESIGN.md token,与 OrderForm 视觉一致。
 */
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
    <Select value={value} onValueChange={onChange} disabled={!onChange}>
      <SelectTrigger className="h-[36px] cursor-pointer gap-xxs rounded-pill border-0 bg-surface-3 px-sm hover:bg-surface-hover">
        <Icon className="size-4 text-text-muted" aria-hidden />
        <SelectValue className="text-body-sm font-semibold text-text-primary" />
      </SelectTrigger>
      <SelectContent>
        {options.map((o) => (
          <SelectItem key={o} value={o}>
            {o}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/**
 * BottomControlBar — 编辑器底部控制栏(照原型 workbench.html)。
 * Pill 控件:交易所 / 交易对 / 时间周期(shadcn Select) + 日期范围(DateRangePicker) + 回测。
 *
 * 交互(非阻塞):改 symbol/interval/exchange 不再弹"创建新策略"
 * 阻塞式 ConfirmDialog,而是就地覆盖回测参数(回测按钮立即可点)。与策略不同时显示非阻塞
 * 内联提示 + "另存为新策略"显式按钮(用户主动点才 fork,不挡回测)。
 *
 * exchange 由父组件(StrategyPage)从 uiStore 取后传入(回测数据获取重构:exchange 不再用
 * 策略字段 selected.exchange — 模拟盘 OKX 账户查 Binance klines 0 行的根因)。点回测 →
 * onSubmitBacktest({startTime, endTime, exchange, symbol, interval})。
 *
 * 日期区间用 DateRangePicker(抽自原内联 Popover+Calendar):硬编码 resetOnSelect=true
 * 修复"起始时间还是一年前"BUG,双月视图免翻页,清空按钮可重置。
 */
export function BottomControlBar({
  symbol,
  interval,
  strategySymbol,
  strategyInterval,
  strategyExchange,
  exchange,
  marketType,
  backtesting,
  onSubmitBacktest,
  onSymbolChange,
  onIntervalChange,
  onExchangeChange,
  onSaveAsNewStrategy,
}: BottomControlBarProps) {
  // 交易对列表接真:usePairs(exchange, marketType) 拉 /market/pairs;空/loading fallback SYMBOLS 5 主流,
  // 确保当前 symbol 在列表(策略 symbol 可能不在 pairs,如跨市场类型)
  const { data: pairs } = usePairs(exchange, marketType ?? 'SPOT')
  const symbolOptions = (() => {
    const list = (pairs ?? []).map((p) => p.symbol).filter((s): s is string => !!s)
    if (list.length === 0) return SYMBOLS
    if (symbol && !list.includes(symbol)) return [symbol, ...list]
    return list
  })()
  // 默认回测区间最近 1 年(量化回测需足够样本,1 年覆盖中频周期;既不过短(噪音)也不过长(计算开销大))。
  const [dateRange, setDateRange] = useState<DateRange | undefined>(() => {
    const to = new Date()
    const from = new Date()
    from.setDate(from.getDate() - 365)
    return { from, to }
  })

  const rangeReady = !!dateRange?.from && !!dateRange?.to

  // symbol/interval/exchange 与策略不同 → 非阻塞提示(就地回测,另存为显式操作)
  const differsFromStrategy =
    (!!strategySymbol && symbol !== strategySymbol) ||
    (!!strategyInterval && interval !== strategyInterval) ||
    (!!strategyExchange && exchange !== strategyExchange)

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
      {/* Exchange selector(父传 uiStore exchange,可跨交易所改选) */}
      <PillSelect icon={Landmark} value={exchange} options={EXCHANGES} onChange={onExchangeChange} />

      {/* Symbol selector(就地覆盖回测 symbol,不再阻塞 fork) */}
      <PillSelect icon={Bitcoin} value={symbol} options={symbolOptions} onChange={onSymbolChange} />

      {/* Timeframe selector(就地覆盖回测 interval) */}
      <PillSelect icon={Clock} value={interval} options={TIMEFRAMES} onChange={onIntervalChange} />

      {/* Date range picker(resetOnSelect=true 修复起始时间 BUG + 双月免翻页) */}
      <DateRangePicker value={dateRange} onChange={setDateRange} />

      {/* 非阻塞"与策略不同"提示 + 显式另存为(不挡回测) */}
      {differsFromStrategy && onSaveAsNewStrategy && (
        <div className="flex items-center gap-xxs rounded-pill border border-warning-soft bg-warning-soft/40 px-sm py-xxs">
          <span className="text-caption text-text-secondary">
            已用 {exchange} · {symbol} · {interval} 回测(与策略不同)
          </span>
          <button
            type="button"
            onClick={onSaveAsNewStrategy}
            className="flex items-center gap-xxs rounded-pill bg-surface-card px-xs py-[2px] text-caption font-medium text-text-primary transition-colors hover:bg-surface-hover"
            title="以当前 exchange/symbol/interval 创建新策略,原策略不变"
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
        data-testid="backtest-run-btn"
      >
        <FlaskConical className="size-4" aria-hidden />
        {backtesting ? '回测中…' : '回测'}
      </Button>
    </div>
  )
}
