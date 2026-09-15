import { useEffect, useState } from 'react'
import { Bitcoin, Clock, FlaskConical, Landmark, Layers, Save } from 'lucide-react'
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
import { Switch } from '@/components/ui/switch'
import { SymbolSelect } from '@/components/SymbolSelect'
import { MultiSymbolSelect, PORTFOLIO_MIN_SYMBOLS } from '@/components/MultiSymbolSelect'

export interface BacktestRange {
  startTime: string
  endTime: string
  exchange: string
  symbol: string
  interval: string
  /** PERP 资金费跨所代理显式开关(默认 false = fail-closed 缺期拒;SPOT 任务后端忽略)。 */
  allowFundingProxy: boolean
  /** 组合(多标的)回测标的列表(≥2 时生效,提交走 symbols 通道、symbol 置空;单标的为 undefined)。 */
  symbols?: string[]
}

interface BottomControlBarProps {
  /** 当前回测用 symbol/interval(controlled by 父，与策略可不同)。 */
  symbol: string
  interval: string
  /** 策略本身的 symbol/interval/exchange(用于检测"与策略不同"显示非阻塞提示)。 */
  strategySymbol: string | undefined
  strategyInterval: string | undefined
  strategyExchange: string | undefined
  /** 回测交易所(父组件从 uiStore 取，默认 'OKX' 项目基准；用户可改选跨交易所)。 */
  exchange: string
  /** 策略市场类型(SPOT/PERP)，用于 usePairs 拉对应交易对；空策略 fallback SPOT。 */
  marketType?: string
  backtesting: boolean
  onSubmitBacktest: (range: BacktestRange) => void
  /** symbol/interval/exchange 改选 → 父 setState(就地覆盖回测参数，不再阻塞式 fork)。 */
  onSymbolChange?: (symbol: string) => void
  onIntervalChange?: (interval: string) => void
  onExchangeChange?: (exchange: string) => void
  /** 显式"另存为新策略"(非阻塞：用户主动点才 fork，回测不受影响)。 */
  onSaveAsNewStrategy?: () => void
  /** retry 跳转预填日期区间(父从 ?taskId&retry 拉任务后传入；非空时覆盖默认"最近 1 年")。 */
  initialDateRange?: { from: Date; to: Date } | null
  /** retry 跳转预填资金费代理开关(上次任务因 FUNDING_DATA 缺期失败时父传 true——
   * 原区间原样重提必再失败,预填开关把出路直接摆到用户面前)。 */
  initialFundingProxy?: boolean
  /** retry 跳转预填组合标的(上次任务是组合回测时父传其 symbols——切组合模式并选中,
   * 防逗号拼接的 task.symbol 污染单标的选择器后重提必败)。 */
  initialSymbols?: string[] | null
}

// 标的由 SymbolSelect 内部 useTradableSymbols 提供(24h 成交额排序 + 搜索 + strip)
const TIMEFRAMES = ['1m', '5m', '15m', '1h', '4h', '1d']
const EXCHANGES = ['OKX', 'BINANCE', 'BITGET']

/**
 * Pill-shaped 下拉选择控件(shadcn Select,SelectTrigger 注入 Pill 外观)。
 *
 * 替换原"原生 <select> + opacity-0 浮层"实现(只换皮不换骨，下拉浮层走浏览器原生 UI
 * 与下单组件不一致)。现在用 components/ui/select.tsx 的 shadcn Select,trigger +
 * 浮层都走 DESIGN.md token，与 OrderForm 视觉一致。
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
 * BottomControlBar — 编辑器底部控制栏。
 * Pill 控件：交易所 / 交易对 / 时间周期(shadcn Select) + 日期范围(DateRangePicker) + 回测。
 *
 * 交互(非阻塞):改 symbol/interval/exchange 不再弹"创建新策略"
 * 阻塞式 ConfirmDialog，而是就地覆盖回测参数(回测按钮立即可点)。与策略不同时显示非阻塞
 * 内联提示 + "另存为新策略"显式按钮(用户主动点才 fork，不挡回测)。
 *
 * exchange 由父组件(StrategyPage)从 uiStore 取后传入(回测数据获取重构:exchange 不再用
 * 策略字段 selected.exchange — 模拟盘 OKX 账户查 Binance klines 0 行的根因)。点回测 →
 * onSubmitBacktest({startTime, endTime, exchange, symbol, interval})。
 *
 * 日期区间用 DateRangePicker(抽自原内联 Popover+Calendar):硬编码 resetOnSelect=true
 * 修复"起始时间还是一年前"BUG，双月视图免翻页，清空按钮可重置。
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
  initialDateRange,
  initialFundingProxy,
  initialSymbols,
}: BottomControlBarProps) {
  // 标的下拉由 SymbolSelect 内部 useTradableSymbols 提供，见下方 JSX
  // 默认回测区间最近 1 年(量化回测需足够样本，1 年覆盖中频周期；既不过短(噪音)也不过长(计算开销大))。
  const [dateRange, setDateRange] = useState<DateRange | undefined>(() => {
    const to = new Date()
    const from = new Date()
    from.setDate(from.getDate() - 365)
    return { from, to }
  })

  // retry 跳转预填：父传入上次回测区间(对象引用变化即应用)→ 覆盖默认"最近 1 年"
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- retry 一次性信号(父 ref guard 保证只传一次)同步到受控 dateRange，非级联渲染；同 StrategyPage queryId 同步模式
    if (initialDateRange) setDateRange({ from: initialDateRange.from, to: initialDateRange.to })
  }, [initialDateRange])

  // PERP 资金费跨所代理开关:默认关(fail-closed)。开启是用户的显式决定——代理值存在
  // 跨所基差风险,报告 warnings 会标注 PROXY_BINANCE 期数(perp-backtest-spec §5/§8)。
  const [allowFundingProxy, setAllowFundingProxy] = useState(false)

  // 组合(多标的)回测模式:策略代码须定义 on_bars(ctx)(入口不匹配时 worker 拒任务,
  // errorMessage 透出)。选择状态是本 bar 的局部态——组合是"这次回测"的属性,不回写策略绑定。
  const [portfolioMode, setPortfolioMode] = useState(false)
  const [portfolioSymbols, setPortfolioSymbols] = useState<string[]>([])
  const portfolioReady = portfolioSymbols.length >= PORTFOLIO_MIN_SYMBOLS

  // retry 预填:上次任务是组合回测 → 切组合模式并选中其标的(与 initialDateRange 同范式,
  // 引用变化即应用;否则 task.symbol 逗号串会污染单标的选择器,重提必败)
  useEffect(() => {
    if (initialSymbols?.length) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- retry 一次性信号(父 ref guard 保证只传一次)同步到受控组合态,同 initialDateRange 模式
      setPortfolioMode(true)
      setPortfolioSymbols(initialSymbols)
    }
  }, [initialSymbols])

  // retry 预填:上次因资金费缺期失败(FUNDING_DATA)→ 开关预填打开(与 initialDateRange 同范式)
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- retry 一次性信号同步到受控开关,同 initialDateRange 模式
    if (initialFundingProxy) setAllowFundingProxy(true)
  }, [initialFundingProxy])

  const rangeReady = !!dateRange?.from && !!dateRange?.to

  // symbol/interval/exchange 与策略不同 → 非阻塞提示(就地回测，另存为显式操作)。
  // 组合模式不适用:多标的天然"与策略单 symbol 绑定不同",且另存为(单 symbol 策略)承载不了
  // 组合语义——提示与另存为在组合模式下隐藏,组合选择器 pill 本身已声明当前口径。
  const differsFromStrategy =
    !portfolioMode &&
    ((!!strategySymbol && symbol !== strategySymbol) ||
      (!!strategyInterval && interval !== strategyInterval) ||
      (!!strategyExchange && exchange !== strategyExchange))

  const handleBacktest = () => {
    if (!dateRange?.from || !dateRange?.to) return
    if (portfolioMode && !portfolioReady) return
    onSubmitBacktest({
      startTime: dateRange.from.toISOString(),
      endTime: dateRange.to.toISOString(),
      exchange,
      symbol,
      interval,
      allowFundingProxy,
      symbols: portfolioMode ? portfolioSymbols : undefined,
    })
  }

  return (
    <div className="flex flex-wrap items-center gap-sm bg-surface-card-2 px-base py-sm">
      {/* Exchange selector(父传 uiStore exchange，可跨交易所改选) */}
      <PillSelect icon={Landmark} value={exchange} options={EXCHANGES} onChange={onExchangeChange} />

      {/* 标的模式切换:单标的 / 组合(多标的,策略须定义 on_bars)。选中态走中性色(DESIGN.md:
          选中不用品牌橙)。组合是本次回测的口径,不回写策略绑定,故为控制栏局部态。 */}
      <div
        className="inline-flex h-[36px] items-center gap-[2px] rounded-pill bg-surface-3 p-[2px]"
        role="radiogroup"
        aria-label="回测标的模式"
      >
        {(
          [
            ['single', '单标的', Bitcoin],
            ['portfolio', '组合', Layers],
          ] as const
        ).map(([mode, label, Icon]) => {
          const active = (mode === 'portfolio') === portfolioMode
          return (
            <button
              key={mode}
              type="button"
              role="radio"
              aria-checked={active}
              data-testid={`backtest-mode-${mode}`}
              onClick={() => setPortfolioMode(mode === 'portfolio')}
              title={
                mode === 'portfolio'
                  ? '组合(多标的)回测:2-20 个标的共享现金池,策略代码须定义 on_bars(ctx)'
                  : '单标的回测:策略代码定义 on_bar(bar, ctx)'
              }
              className={`flex h-full items-center gap-xxs rounded-pill px-sm text-caption font-semibold transition-colors ${
                active
                  ? 'bg-interactive-selected text-text-primary'
                  : 'text-text-muted hover:text-text-secondary'
              }`}
            >
              <Icon className="size-3.5" aria-hidden />
              {label}
            </button>
          )
        })}
      </div>

      {/* Symbol selector(就地覆盖回测 symbol,Combobox 搜索+成交额;组合模式换多选器) */}
      {portfolioMode ? (
        <MultiSymbolSelect
          values={portfolioSymbols}
          onChange={setPortfolioSymbols}
          exchange={exchange}
          marketType={marketType ?? 'SPOT'}
        />
      ) : (
        <SymbolSelect
          value={symbol}
          onChange={onSymbolChange ?? (() => {})}
          exchange={exchange}
          marketType={marketType ?? 'SPOT'}
          trigger="pill"
          icon={Bitcoin}
        />
      )}

      {/* Timeframe selector(就地覆盖回测 interval) */}
      <PillSelect icon={Clock} value={interval} options={TIMEFRAMES} onChange={onIntervalChange} />

      {/* Date range picker(resetOnSelect=true 修复起始时间 BUG + 双月免翻页) */}
      <DateRangePicker value={dateRange} onChange={setDateRange} />

      {/* PERP 专属:资金费跨所代理开关(默认关 = 缺期 fail-closed 拒;SPOT 不渲染,后端也忽略) */}
      {marketType === 'PERP' && (
        <div
          className="flex items-center gap-xxs rounded-pill bg-surface-3 px-sm py-xxs"
          title="目标交易所资金费历史缺期时,用 Binance 同期次值补齐(报告 warnings 标注 PROXY_BINANCE,存在跨所基差风险);关闭时缺期将拒绝回测"
        >
          <Switch
            id="allow-funding-proxy"
            checked={allowFundingProxy}
            onCheckedChange={setAllowFundingProxy}
            data-testid="funding-proxy-switch"
          />
          <label
            htmlFor="allow-funding-proxy"
            className="cursor-pointer select-none text-caption font-semibold text-text-primary"
          >
            资金费代理
          </label>
        </div>
      )}

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
            title="以当前交易所、交易对、周期创建新策略，原策略不变"
          >
            <Save className="size-3" aria-hidden />
            另存为新策略
          </button>
        </div>
      )}

      <div className="flex-1" />

      {/* Backtest button (需先选日期范围;组合模式还需 ≥2 标的;PERP 已支持——资金费缺期等
          预检失败由后端 400 文案透出) */}
      <Button
        variant="outline"
        size="default"
        onClick={handleBacktest}
        disabled={!rangeReady || backtesting || (portfolioMode && !portfolioReady)}
        title={portfolioMode && !portfolioReady ? `组合回测需选择至少 ${PORTFOLIO_MIN_SYMBOLS} 个标的` : undefined}
        data-testid="backtest-run-btn"
      >
        <FlaskConical className="size-4" aria-hidden />
        {backtesting ? '回测中…' : '回测'}
      </Button>
    </div>
  )
}
