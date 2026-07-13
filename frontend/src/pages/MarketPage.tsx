import { useState } from 'react'
import { toast } from 'sonner'
import { Heart, Bell } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { SectionTitle } from '@/components/SectionTitle'
import { Chip } from '@/components/Chip'
import { LivePrice } from '@/components/LivePrice'
import { SparklineChart } from '@/components/charts/SparklineChart'
import { KlineChart, type KlineCandle } from '@/components/charts/KlineChart'
import { HeatmapChart } from '@/components/charts/HeatmapChart'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { useTickers, useKlines, useSubscribeMarket } from '@/hooks/useMarket'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * MarketPage — 行情页(照原型 done-design/components/MarketPage.jsx port)。
 *
 * 5 块:Ticker grid(6 symbol 卡,点击切 sel)/ K 线详情 + 订单簿 / 订阅状态 + PAPER 行情来源 / 板块涨跌热度。
 *
 * 适配后端契约(honest 差异,不静默照做,记 docs/tech-debt.md TD-008~012):
 *  - tickers 列表:后端无"列表 ticker"端点(单 symbol GET /ticker/{e}/{m}/{s})→ hardcode
 *    MARKET_SYMBOLS 循环 useTickers(useQueries 批量),TD-008
 *  - sel K 线:GET /market/klines?exchange&marketType&symbol&interval&limit → Kline[] → KlineCandle
 *  - 订单簿:后端无 order book 端点 → 硬编码 mock(基于 sel.last 派生 asks/bids,稳定无随机),TD-009
 *  - 板块热度 Heatmap 多周期:后端 ticker 只单点 percentage,无多周期 → 用 percentage 派生 6 周期 mock,TD-010
 *  - 订阅按钮:POST /market/subscribe(SubscribeRequest{exchange,marketType,symbol})→ WS 推送
 *    管理推 marketStore 阶段4 补全,当前 POST 占位 toast,TD-011
 *  - PAPER 行情来源:静态占位(基准 BINANCE/延迟/通道),TD-012
 *  - LivePrice:脚手架 LivePrice(symbol, base, dp) 内部 marketStore tickerTick 闪烁,不接 chg(chg 在外部 span)
 *
 * 金额:价格(last/bid/ask/high/low)展示 formatMoney(toDecimal, {dp}),全 kq-mono-row;
 * 订单簿总额 mock 用 toDecimal(px).times(qty) decimal 运算(金额红线)。
 * 涨跌(chg%)用 pnlArrow + pnlTextClass(a11y 箭头+色,不靠色单独表达),入参 toDecimal(percentage).toNumber()。
 * 图标全 lucide-react(Heart 订阅自选 / Bell 订阅 sel),不用 emoji(♥)。
 * 无破坏性操作(订阅是 WS 订阅非破坏,不补 Confirm)。
 */
type TickerResponse = components['schemas']['TickerResponse']
type Ticker = components['schemas']['Ticker']

const EXCHANGE = 'BINANCE'
const MARKET_TYPE = 'SPOT'
const MARKET_SYMBOLS = [
  'BTC/USDT',
  'ETH/USDT',
  'SOL/USDT',
  'BNB/USDT',
  'XRP/USDT',
  'DOGE/USDT',
] as const

const INTERVAL_TABS = [
  { label: '1m', value: '_1m' },
  { label: '5m', value: '_5m' },
  { label: '15m', value: '_15m' },
  { label: '1h', value: '_1h' },
  { label: '4h', value: '_4h' },
  { label: '1d', value: '_1d' },
] as const

/** 价格小数位:last<1 用 4dp,否则 2dp(对齐原型 t.last<1?4:2)。 */
function dpFor(v: number | undefined): number {
  return v != null && v < 1 ? 4 : 2
}

/** 价格格式化(千分位 + dp,金额红线走 toDecimal+formatMoney)。 */
function fmtPrice(v: number | undefined, dp: number): string {
  if (v == null) return '—'
  return formatMoney(toDecimal(v), { dp })
}

export function MarketPage() {
  const [sel, setSel] = useState<string>(MARKET_SYMBOLS[0]!)
  const [interval, setIntervalTab] = useState<string>('_15m')
  const subscribeMut = useSubscribeMarket()

  const tickerResults = useTickers(EXCHANGE, MARKET_TYPE, [...MARKET_SYMBOLS])
  const tickers: TickerResponse[] = tickerResults
    .map((r) => r.data)
    .filter((d): d is TickerResponse => !!d)

  const selIdx = MARKET_SYMBOLS.indexOf(sel as (typeof MARKET_SYMBOLS)[number])
  const selRes = tickerResults[selIdx >= 0 ? selIdx : 0]
  const selTicker = selRes?.data?.ticker
  const selStale = selRes?.data?.stale ?? false
  const selPct = toDecimal(selTicker?.percentage ?? 0).toNumber()

  const klines = useKlines({
    exchange: EXCHANGE,
    marketType: MARKET_TYPE,
    symbol: sel,
    interval,
    limit: 60,
  })
  const candles: KlineCandle[] = (klines.data ?? []).map((k) => ({
    ts: k.openTime ?? '',
    o: k.open ?? 0,
    h: k.high ?? 0,
    l: k.low ?? 0,
    c: k.close ?? 0,
    v: k.volume ?? 0,
  }))

  const handleSubscribe = (symbol: string) => {
    subscribeMut.mutate(
      { exchange: EXCHANGE, marketType: MARKET_TYPE, symbol },
      {
        onSuccess: () => toast.success(`已订阅 ${symbol}(WS 推送待 marketStore 阶段4 接通)`),
        onError: () => toast.error('订阅失败,请重试'),
      },
    )
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <div>
          <h1 className="text-h2 font-bold tracking-[-0.015em] text-text-primary">行情</h1>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            实时价格 · 历史 K 线 · ticker 与 K 线 WS 推送
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => toast.info('订阅自选(待自选列表)')}>
            <Heart className="size-4" aria-hidden />
            订阅自选
          </Button>
          <Button size="sm" onClick={() => handleSubscribe(sel)}>
            <Bell className="size-4" aria-hidden />
            订阅 {sel}
          </Button>
        </div>
      </div>

      {/* Ticker grid */}
      <div className="grid grid-cols-4 gap-2.5 max-[1100px]:grid-cols-2 max-[560px]:grid-cols-1">
        {tickerResults.map((r, i) => {
          const symbol = MARKET_SYMBOLS[i]!
          return (
            <TickerCard
              key={symbol}
              symbol={symbol}
              loading={r.isLoading}
              error={r.isError}
              data={r.data}
              selected={symbol === sel}
              onSelect={() => setSel(symbol)}
            />
          )
        })}
      </div>

      {/* K-line detail + Order book */}
      <div className="grid grid-cols-[1fr_300px] items-start gap-[18px] max-[1100px]:grid-cols-1">
        {/* K-line */}
        <Card className="overflow-hidden p-0">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border-soft px-4 py-3">
            <div className="flex flex-wrap items-center gap-2.5">
              <strong className="text-body font-bold text-text-primary">{sel}</strong>
              {selStale && <Chip label="STALE · 行情已断" color="warning" />}
              {selTicker && (
                <LivePrice symbol={sel} base={selTicker.last ?? 0} dp={dpFor(selTicker.last)} />
              )}
              <span
                className={`kq-mono-row text-body-sm font-bold ${pnlTextClass(selPct)}`}
              >
                {pnlArrow(selPct)} {selPct >= 0 ? '+' : ''}{selPct}%
              </span>
            </div>
            <Tabs value={interval} onValueChange={setIntervalTab}>
              <TabsList>
                {INTERVAL_TABS.map((t) => (
                  <TabsTrigger key={t.value} value={t.value}>
                    {t.label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          </div>
          <div className="min-w-0 p-3">
            {klines.isLoading ? (
              <LoadingState rows={4} />
            ) : klines.error ? (
              <ErrorState message={(klines.error as Error).message} onRetry={() => klines.refetch()} />
            ) : (
              <KlineChart data={candles} height={340} />
            )}
          </div>
          <div className="flex flex-wrap gap-[18px] border-t border-border-soft px-4 py-2.5 text-[11px] text-text-muted">
            <span>
              24H 涨跌{' '}
              <span className={`kq-mono-row font-bold ${pnlTextClass(selPct)}`}>
                {pnlArrow(selPct)} {selPct >= 0 ? '+' : ''}{selPct}%
              </span>
            </span>
            <span>最高 <span className="kq-mono-row">{fmtPrice(selTicker?.high, 0)}</span></span>
            <span>最低 <span className="kq-mono-row">{fmtPrice(selTicker?.low, 0)}</span></span>
            <span>24H 量 <span className="kq-mono-row">{fmtPrice(selTicker?.baseVolume, 0)}</span></span>
            <span>买一 <span className="kq-mono-row text-up">{fmtPrice(selTicker?.bid, dpFor(selTicker?.last))}</span></span>
            <span>卖一 <span className="kq-mono-row text-down">{fmtPrice(selTicker?.ask, dpFor(selTicker?.last))}</span></span>
          </div>
        </Card>

        {/* Order book */}
        <OrderBook symbol={sel} last={selTicker?.last ?? 0} pct={selPct} />
      </div>

      {/* Subscription + PAPER source */}
      <div className="grid grid-cols-2 gap-[18px] max-[1100px]:grid-cols-1">
        <Card className="p-5">
          <SectionTitle title="订阅状态" sub="REST 订阅 / WS 接收" />
          <div className="grid grid-cols-2 gap-2 text-body-sm">
            {MARKET_SYMBOLS.map((s, i) => {
              const r = tickerResults[i]
              const stale = r?.data?.stale ?? false
              return (
                <div
                  key={s}
                  className="flex items-center justify-between rounded-lg bg-surface-card-2 px-2.5 py-1.5"
                >
                  <span>{s}</span>
                  <div className="flex items-center gap-1.5">
                    <span className={`kq-pulse size-1.5 rounded-full ${stale ? 'bg-warning' : 'bg-up'}`} />
                    <span className="text-[10px] text-text-muted">{stale ? '断开' : '订阅中'}</span>
                  </div>
                </div>
              )
            })}
          </div>
        </Card>

        <Card className="p-5">
          <SectionTitle title="PAPER 行情来源" sub="PAPER 无自身行情" />
          <div className="flex flex-col gap-2">
            <div className="rounded-lg bg-surface-card-2 p-2.5 text-[11px] leading-[1.6] text-text-secondary">
              PAPER 账户走基准交易所 <strong className="text-text-primary">BINANCE</strong>{' '}
              行情。UI 不允许对 PAPER 直接查行情,需通过基准交易所。
            </div>
            <div className="grid grid-cols-3 gap-2 text-[11px]">
              <div className="rounded-lg bg-surface-card-2 p-2.5">
                <div className="text-[9px] uppercase tracking-[0.06em] text-text-muted">基准</div>
                <div className="mt-0.5 font-bold">BINANCE</div>
              </div>
              <div className="rounded-lg bg-surface-card-2 p-2.5">
                <div className="text-[9px] uppercase tracking-[0.06em] text-text-muted">延迟</div>
                <div className="kq-mono-row mt-0.5 font-bold text-up">12 ms</div>
              </div>
              <div className="rounded-lg bg-surface-card-2 p-2.5">
                <div className="text-[9px] uppercase tracking-[0.06em] text-text-muted">通道</div>
                <div className="mt-0.5 font-bold">WS · L2</div>
              </div>
            </div>
          </div>
        </Card>
      </div>

      {/* Sector heatmap */}
      <Card className="p-5">
        <SectionTitle
          title="板块涨跌热度"
          sub="多币种 × 多周期 · 行末为行均值"
          right={<Chip label="DENSITY" color="accent" />}
        />
        <div className="overflow-x-auto pb-1">
          <HeatmapChart
            data={tickers.map((t) => {
              const base = toDecimal(t.ticker.percentage ?? 0).toNumber()
              return [base * 0.3 + 0.4, base * 0.5 + 0.2, base * 0.7 - 0.1, base * 0.9, base * 1.1, base].map(
                (v) => Math.round(v * 100) / 100,
              )
            })}
            rowLabels={tickers.map((t) => (t.ticker.symbol ?? '').replace('/USDT', ''))}
            colLabels={['1m', '5m', '15m', '1h', '4h', '1d']}
            cellW={70}
            cellH={36}
          />
        </div>
      </Card>
    </div>
  )
}

/** TickerCard — 单 symbol 行情卡(照原型 line 38-55 抄)。 */
function TickerCard({
  symbol,
  loading,
  error,
  data,
  selected,
  onSelect,
}: {
  symbol: string
  loading: boolean
  error: boolean
  data: TickerResponse | undefined
  selected: boolean
  onSelect: () => void
}) {
  const t: Ticker | undefined = data?.ticker
  const stale = data?.stale ?? false
  const pct = toDecimal(t?.percentage ?? 0).toNumber()
  const dp = dpFor(t?.last)

  return (
    <button
      type="button"
      onClick={onSelect}
      className={`rounded-[10px] border p-3 text-left transition-all ${
        selected
          ? 'border-accent bg-accent-soft'
          : 'border-border-soft bg-surface-card hover:border-text-muted'
      }`}
    >
      <div className="flex items-baseline justify-between">
        <strong className="text-body-sm">{symbol}</strong>
        {stale ? (
          <Chip label="STALE" color="warning" />
        ) : (
          <span className="kq-pulse size-1.5 rounded-full bg-up" />
        )}
      </div>
      <div className="mt-1.5 flex items-baseline justify-between">
        {loading ? (
          <span className="text-[11px] text-text-muted">加载中…</span>
        ) : error ? (
          <span className="text-[11px] text-text-muted">连接失败</span>
        ) : t ? (
          <LivePrice symbol={symbol} base={t.last ?? 0} dp={dp} />
        ) : (
          <span className="text-[11px] text-text-muted">无数据</span>
        )}
        <span className={`kq-mono-row text-[11px] font-bold ${t ? pnlTextClass(pct) : 'text-text-muted'}`}>
          {t ? `${pnlArrow(pct)} ${pct >= 0 ? '+' : ''}${pct}%` : '—'}
        </span>
      </div>
      <div className="mt-1.5">
        <SparklineChart
          data={[1, 2, 3, 2, 4, 3, 5, 4, 6, 5, 7, pct >= 0 ? 8 : 6]}
          width={180}
          height={20}
        />
      </div>
      <div className="mt-1.5 flex justify-between text-[10px] text-text-muted">
        <span>24H H <span className="kq-mono-row">{fmtPrice(t?.high, dp === 4 ? 4 : 0)}</span></span>
        <span>L <span className="kq-mono-row">{fmtPrice(t?.low, dp === 4 ? 4 : 0)}</span></span>
        <span>Vol <span className="kq-mono-row">{fmtPrice(t?.baseVolume, 0)}</span></span>
      </div>
    </button>
  )
}

/** OrderBook — 订单簿深度(照原型 line 86-128 抄)。honest:后端无 order book 端点,硬编码 mock(TD-009)。 */
function OrderBook({
  symbol,
  last,
  pct,
}: {
  symbol: string
  last: number
  pct: number
}) {
  // mock:基于 last 派生 6 asks + 6 bids,稳定无随机(i 派生 qty)。
  // 金额红线:px 是价格(金额),用 decimal.js 派生(若 last 是 string,JS -/+ 会隐式转丢精度);
  // qty 是数量(币数)非金额,number OK;total 在 OrderRow 走 toDecimal(px).times(qty)。
  const asks = Array.from({ length: 6 }, (_, i) => {
    const px = toDecimal(last).minus(toDecimal(i + 1).times(0.5)).toNumber()
    const qty = 0.1 * (i + 1) + 0.05
    return { px, qty }
  })
  const bids = Array.from({ length: 6 }, (_, i) => {
    const px = toDecimal(last).plus(toDecimal(i + 1).times(0.5)).toNumber()
    const qty = 0.1 * (i + 1) + 0.05
    return { px, qty }
  })

  return (
    <Card className="flex flex-col p-0">
      <div className="flex items-center justify-between border-b border-border-soft px-3.5 py-3">
        <div>
          <div className="text-body-sm font-bold">订单簿深度</div>
          <div className="text-[10px] text-text-muted">{symbol}</div>
        </div>
        <span className="kq-live-badge">L2</span>
      </div>
      <div className="grid grid-cols-3 px-3.5 pb-1 pt-1.5 text-[9px] uppercase tracking-[0.06em] text-text-muted">
        <span>价格</span>
        <span className="text-right">数量</span>
        <span className="text-right">总额</span>
      </div>
      {/* asks (卖) */}
      <div className="px-3.5 pb-2 text-[11px]">
        {asks.map((r, i) => (
          <OrderRow key={'a' + i} px={r.px} qty={r.qty} side="ask" />
        ))}
      </div>
      <div className="flex items-center justify-between border-y border-border-soft bg-surface-card-2 px-3.5 py-2">
        <span className="text-[11px] text-text-muted">买一 / 卖一</span>
        <span className={`kq-mono-row text-body-sm font-bold ${pnlTextClass(pct)}`}>
          {fmtPrice(last, last < 1 ? 4 : 2)}
        </span>
        <span className="text-[10px] text-text-muted">点差 {fmtPrice(0.5, 2)}</span>
      </div>
      {/* bids (买) */}
      <div className="px-3.5 py-2 text-[11px]">
        {bids.map((r, i) => (
          <OrderRow key={'b' + i} px={r.px} qty={r.qty} side="bid" />
        ))}
      </div>
    </Card>
  )
}

/** OrderRow — 订单簿单行(深度条 + 价格/数量/总额)。总额 mock 用 decimal.js(金额红线)。 */
function OrderRow({ px, qty, side }: { px: number; qty: number; side: 'ask' | 'bid' }) {
  const total = toDecimal(px).times(toDecimal(qty))
  const depthPct = Math.min(100, qty * 180)
  return (
    <div className="kq-mono-row relative grid grid-cols-3 py-[3px]">
      <span className={side === 'ask' ? 'text-up' : 'text-down'}>{fmtPrice(px, 2)}</span>
      <span className="text-right">{fmtPrice(qty, 4)}</span>
      <span className="text-right text-text-muted">{formatMoney(total, { dp: 0 })}</span>
      <span
        className={`absolute top-1 bottom-1 ${side === 'ask' ? 'right-0' : 'left-0'} z-0 rounded`}
        style={{
          width: `${depthPct}%`,
          background:
            side === 'ask'
              ? 'linear-gradient(90deg,transparent,color-mix(in oklab, var(--up) 10%, transparent))'
              : 'linear-gradient(270deg,transparent,color-mix(in oklab, var(--down) 10%, transparent))',
          pointerEvents: 'none',
        }}
      />
    </div>
  )
}
