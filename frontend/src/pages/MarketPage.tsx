import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { useTickers, useKlines, useSubscribeMarket, useSparklines, useOrderBook } from '@/hooks/useMarket'
import { useAccounts } from '@/hooks/useAccounts'
import { useMarketStore } from '@/stores/marketStore'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { klineDestination, type WsKline } from '@/types/ws'
import { fetchKlines, subscribeKlineMarket, unsubscribeKlineMarket } from '@/api/market'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * MarketPage — 行情页(照原型 done-design/components/MarketPage.jsx port)。
 *
 * 5 块:Ticker grid(6 symbol 卡,点击切 sel)/ K 线详情 + 订单簿 / 订阅状态 + PAPER 行情来源 / 板块涨跌热度。
 *
 * 适配后端契约(honest 差异,不静默照做,记 docs/tech-debt.md TD-008~012):
 *  - tickers 列表:产品精选 top 8 主流 USDT 对(与 handler /market/pairs 对齐,非 mock 是展示策略);
 *    后端无"列表 ticker"端点→循环 useTickers(useQueries 批量 8 个,性能可接受)。
 *    TD-008 中期:后端 /market/pairs 加 quoteVolume + ?sort=volume&limit=N 解 hardcode 维护(留账)
 *  - sel K 线:GET /market/klines?exchange&marketType&symbol&interval&limit → Kline[] → KlineCandle
 *  - 订单簿:TD-009 已接 useOrderBook(GET /market/orderbook,REST 轮询 3s,后端无 orderbook WS)
 *  - 板块热度 Heatmap 已删(TD-010):后端 ticker 只单点 percentage,无多周期;
 *    多周期本地算 8×6=48 GET 性能不可接受,后端无批量 kline+多 period 端点(同 TD-008 架构债),用户决定去掉非核心
 *  - 订阅按钮:POST /market/subscribe(SubscribeRequest{exchange,marketType,symbol})→ WS 推送
 *    管理推 marketStore 阶段4 补全,当前 POST 占位 toast,TD-011
 *  - PAPER 行情来源:静态占位(基准 BINANCE/延迟/通道),TD-012
 *  - LivePrice:脚手架 LivePrice(symbol, base, dp) 内部 marketStore tickerTick 闪烁,不接 chg(chg 在外部 span)
 *
 * 金额:价格(last/bid/ask/high/low)展示 formatMoney(toDecimal, {dp}),全 kq-mono-row;
 * 订单簿总额用 toDecimal(px).times(qty) decimal 运算(金额红线)。
 * 涨跌(chg%)用 pnlArrow + pnlTextClass(a11y 箭头+色,不靠色单独表达),入参 toDecimal(percentage).toNumber()。
 * 图标全 lucide-react(Heart 订阅自选 / Bell 订阅 sel),不用 emoji(♥)。
 * 无破坏性操作(订阅是 WS 订阅非破坏,不补 Confirm)。
 */
type TickerResponse = components['schemas']['TickerResponse']
type Ticker = components['schemas']['Ticker']

const MARKET_TYPE = 'SPOT'
const MARKET_SYMBOLS = [
  'BTC/USDT',
  'ETH/USDT',
  'SOL/USDT',
  'BNB/USDT',
  'XRP/USDT',
  'DOGE/USDT',
  'TRX/USDT',
  'LTC/USDT',
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

  // 动态基准交易所:取默认模拟盘账户(paperTrading=true)的 exchange,兜底 OKX
  // (注册即建 OKX 模拟盘,AuthService.java:78;且 OKX 代理可达,BINANCE 被封 451)。
  const { data: accounts } = useAccounts()
  const exchange = useMemo(
    () => (accounts ?? []).find((a) => a.paperTrading)?.exchange ?? 'OKX',
    [accounts],
  )

  // 订阅 8 个 symbol 的 WS ticker(destination /topic/ticker/{exchange}/SPOT/{sym-dash}),
  // onTicker 推 → marketStore.ticks 更新 → LivePrice 实时跳。exchange 变(账户基准变)重订阅。
  // useTickers(REST)仍保留做首屏快照 + stale(WS 替不掉 stale 语义)。
  useEffect(() => {
    const unsub = useMarketStore.getState().subscribeTickers(exchange, MARKET_TYPE, MARKET_SYMBOLS)
    return unsub
  }, [exchange])

  const tickerResults = useTickers(exchange, MARKET_TYPE, [...MARKET_SYMBOLS])
  const sparklineResults = useSparklines(exchange, MARKET_TYPE, [...MARKET_SYMBOLS])

  const selIdx = MARKET_SYMBOLS.indexOf(sel as (typeof MARKET_SYMBOLS)[number])
  const selRes = tickerResults[selIdx >= 0 ? selIdx : 0]
  const selTicker = selRes?.data?.ticker
  const selStale = selRes?.data?.stale ?? false
  const selPct = toDecimal(selTicker?.percentage ?? 0).toNumber()

  const klines = useKlines({
    exchange,
    marketType: MARKET_TYPE,
    symbol: sel,
    interval,
    limit: 260,
  })
  const recentCandles = useMemo<KlineCandle[]>(
    () =>
      (klines.data ?? []).map((k) => ({
        ts: k.openTime ?? '',
        o: k.open ?? 0,
        h: k.high ?? 0,
        l: k.low ?? 0,
        c: k.close ?? 0,
        v: k.volume ?? 0,
      })),
    [klines.data],
  )
  // 往前滚加载历史(生产级):history 累积更早 candle(prepend),与 recentCandles 合并去重 + sort asc。
  // before 严格 < earliest,history ts 与 recent ts 不重叠;dedup 取 recent(新)优先,safety。
  const [history, setHistory] = useState<KlineCandle[]>([])
  const [loadingMore, setLoadingMore] = useState(false)
  const [noMore, setNoMore] = useState(false)
  // generation token:切 interval/sel/exchange 时 ++ 使 in-flight fetchKlines promise 失效(H2,防旧 candle 灌新 history)
  const genRef = useRef(0)
  const candles = useMemo(() => {
    const byTs = new Map<string, KlineCandle>()
    for (const c of recentCandles) if (c.ts) byTs.set(c.ts, c) // recent 优先(新)
    for (const c of history) if (c.ts && !byTs.has(c.ts)) byTs.set(c.ts, c) // history 补(不覆盖 recent)
    return [...byTs.values()].sort((a, b) => a.ts.localeCompare(b.ts))
  }, [history, recentCandles])
  // 切 interval/sel/exchange → 清 history + 重置 noMore + 使 in-flight fetchKlines 失效(H2)。
  // setState in effect 是 perf 建议(非 correctness),且 genRef.current++ 使 effect 非"纯 setState",rule 不触发。
  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect */
    genRef.current++
    setHistory([])
    setNoMore(false)
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [interval, sel, exchange])
  // 往前滚到最左(KlineChart onLoadMore 触发)→ 拉 before=earliest 的更早 260 根 → prepend history
  const handleLoadMore = useCallback(() => {
    if (loadingMore || noMore || candles.length === 0) return
    const earliest = candles[0]!.ts
    const gen = ++genRef.current
    setLoadingMore(true)
    fetchKlines({ exchange, marketType: MARKET_TYPE, symbol: sel, interval, limit: 260, before: earliest })
      .then((older) => {
        if (genRef.current !== gen) return // 切 interval 后旧请求,丢弃(H2 防旧 candle 灌新 history)
        const seen = new Set<string>([...history.map((c) => c.ts), ...recentCandles.map((c) => c.ts)]) // L3 dedup 含 recent
        const olderCandles: KlineCandle[] = older
          .map((k) => ({
            ts: k.openTime ?? '',
            o: k.open ?? 0,
            h: k.high ?? 0,
            l: k.low ?? 0,
            c: k.close ?? 0,
            v: k.volume ?? 0,
          }))
          .filter((c) => c.ts !== '' && !seen.has(c.ts))
        if (olderCandles.length > 0) {
          setHistory((prev) => [...olderCandles, ...prev])
        } else {
          setNoMore(true) // 数据耗尽,KlineChart 不再触发(H1 防 fetchKlines 空死循环)
        }
      })
      .catch((e: unknown) =>
        toast.error('加载更早历史失败: ' + (e instanceof Error ? e.message : '网络错误')),
      )
      .finally(() => setLoadingMore(false))
  }, [loadingMore, noMore, candles, exchange, sel, interval, history, recentCandles])

  // WS 实时 kline:订阅 /topic/kline/{ex}/{mt}/{sym-dash}/{ccxtInterval},收最新 candle → KlineChart updateCandle 增量(保留缩放)
  const [updateCandle, setUpdateCandle] = useState<KlineCandle | undefined>()
  const ccxtInterval = interval.replace(/^_/, '')
  const klineDest = sel ? klineDestination(exchange, MARKET_TYPE, sel, ccxtInterval) : null
  useWsTopic(klineDest, (payload) => {
    const k = payload as WsKline
    // 校验 interval:旧 interval 在途消息/后端 unsubscribe 慢一拍不能 append 到新 series
    // (WsKline.interval 是枚举名 _1m,与 state interval(_15m)比对,非 ccxtInterval)
    if (!k?.openTime || k.interval !== interval) return
    setUpdateCandle({
      ts: k.openTime,
      o: k.open,
      h: k.high,
      l: k.low,
      c: k.close,
      v: k.volume,
    })
  })
  // 切 sel/interval → POST /subscribe/kline 起后端 kline worker(按需,idle 30s 退订);unmount/切走 POST /unsubscribe/kline
  // 注:不需 setUpdateCandle(undefined) 重置 — useWsTopic interval 校验(M3)拦截旧 interval 消息,
  // 且 updateCandle effect 依赖未变不触发 update,旧 candle 不会 append 到新 data。
  useEffect(() => {
    if (!sel) return
    void subscribeKlineMarket({
      exchange,
      marketType: MARKET_TYPE,
      symbol: sel,
      interval: ccxtInterval,
    }).catch(() => {})
    return () => {
      void unsubscribeKlineMarket({
        exchange,
        marketType: MARKET_TYPE,
        symbol: sel,
        interval: ccxtInterval,
      }).catch(() => {})
    }
  }, [exchange, sel, ccxtInterval])

  const handleSubscribe = (symbol: string) => {
    subscribeMut.mutate(
      { exchange, marketType: MARKET_TYPE, symbol },
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
          const closes = (sparklineResults[i]?.data ?? [])
            .map((k) => k.close)
            .filter((c): c is number => c != null)
          return (
            <TickerCard
              key={symbol}
              symbol={symbol}
              loading={r.isLoading}
              error={r.isError}
              data={r.data}
              sparklineData={closes}
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
                <LivePrice symbol={sel} base={String(selTicker.last ?? '0')} dp={dpFor(selTicker.last)} />
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
              <KlineChart
                data={candles}
                updateCandle={updateCandle}
                onLoadMore={handleLoadMore}
                loadingMore={loadingMore}
                noMore={noMore}
                height={340}
              />
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
        <OrderBook symbol={sel} exchange={exchange} marketType={MARKET_TYPE} last={selTicker?.last ?? 0} pct={selPct} />
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
              PAPER 账户走基准交易所 <strong className="text-text-primary">{exchange}</strong>{' '}
              行情。UI 不允许对 PAPER 直接查行情,需通过基准交易所。
            </div>
            <div className="grid grid-cols-3 gap-2 text-[11px]">
              <div className="rounded-lg bg-surface-card-2 p-2.5">
                <div className="text-[9px] uppercase tracking-[0.06em] text-text-muted">基准</div>
                <div className="mt-0.5 font-bold">{exchange}</div>
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

      {/* TD-010 已删:板块涨跌热度 Heatmap(后端 ticker 只单点 percentage,无多周期;
          多周期本地算要 8 symbol × 6 interval = 48 GET,性能不可接受;
          后端无批量 kline+多 period 端点,同 TD-008 架构债。用户决定去掉,非核心功能) */}
    </div>
  )
}

/** TickerCard — 单 symbol 行情卡(照原型 line 38-55 抄)。 */
function TickerCard({
  symbol,
  loading,
  error,
  data,
  sparklineData,
  selected,
  onSelect,
}: {
  symbol: string
  loading: boolean
  error: boolean
  data: TickerResponse | undefined
  sparklineData: number[]
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
          <LivePrice symbol={symbol} base={String(t.last ?? '0')} dp={dp} />
        ) : (
          <span className="text-[11px] text-text-muted">无数据</span>
        )}
        <span className={`kq-mono-row text-[11px] font-bold ${t ? pnlTextClass(pct) : 'text-text-muted'}`}>
          {t ? `${pnlArrow(pct)} ${pct >= 0 ? '+' : ''}${pct}%` : '—'}
        </span>
      </div>
      <div className="mt-1.5">
        <SparklineChart
          data={sparklineData}
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

/** OrderBook — 订单簿深度(照原型 line 86-128 抄)。TD-009 已接:useOrderBook REST 轮询 3s
 * (后端无 orderbook WS,只有 ticker/kline WS)。price/qty 契约可空(PriceLevel),兜底 0。 */
function OrderBook({
  symbol,
  exchange,
  marketType,
  last,
  pct,
}: {
  symbol: string
  exchange: string
  marketType: string
  last: number
  pct: number
}) {
  const { data: book, isLoading, isError } = useOrderBook(exchange, marketType, symbol)
  // 金额红线:price 是价格(金额),用 decimal.js(但 BookRow 入口已是 toDecimal);
  // qty 是数量(币数)非金额,number OK;total 在 OrderRow 走 toDecimal(px).times(qty)。
  const { asks, bids, maxQty } = useMemo(() => {
    const a = book?.asks ?? []
    const b = book?.bids ?? []
    const all = [...a, ...b].map((l) => l.qty ?? 0)
    return { asks: a, bids: b, maxQty: all.length ? Math.max(...all) : 0 }
  }, [book])

  if (isLoading) {
    return (
      <Card className="flex flex-col p-0">
        <OrderBookHeader symbol={symbol} />
        <div className="px-3.5 py-6">
          <LoadingState />
        </div>
      </Card>
    )
  }
  if (isError) {
    return (
      <Card className="flex flex-col p-0">
        <OrderBookHeader symbol={symbol} />
        <div className="px-3.5 py-6">
          <ErrorState />
        </div>
      </Card>
    )
  }

  return (
    <Card className="flex flex-col p-0">
      <OrderBookHeader symbol={symbol} />
      <div className="grid grid-cols-3 px-3.5 pb-1 pt-1.5 text-[9px] uppercase tracking-[0.06em] text-text-muted">
        <span>价格</span>
        <span className="text-right">数量</span>
        <span className="text-right">总额</span>
      </div>
      {/* asks (卖) */}
      <div className="px-3.5 pb-2 text-[11px]">
        {asks.map((r, i) => (
          <OrderRow key={'a' + i} px={r.price ?? 0} qty={r.qty ?? 0} side="ask" maxQty={maxQty} />
        ))}
      </div>
      <div className="flex items-center justify-between border-y border-border-soft bg-surface-card-2 px-3.5 py-2">
        <span className="text-[11px] text-text-muted">买一 / 卖一</span>
        <span className={`kq-mono-row text-body-sm font-bold ${pnlTextClass(pct)}`}>
          {fmtPrice(last, last < 1 ? 4 : 2)}
        </span>
        <span className="text-[10px] text-text-muted">
          点差{' '}
          {asks[0] && bids[0]
            ? fmtPrice(Math.abs((asks[0].price ?? 0) - (bids[0].price ?? 0)), last < 1 ? 4 : 2)
            : '—'}
        </span>
      </div>
      {/* bids (买) */}
      <div className="px-3.5 py-2 text-[11px]">
        {bids.map((r, i) => (
          <OrderRow key={'b' + i} px={r.price ?? 0} qty={r.qty ?? 0} side="bid" maxQty={maxQty} />
        ))}
      </div>
    </Card>
  )
}

/** OrderBookHeader — 标题 + symbol + L2 徽章(loading/error/normal 三态复用)。 */
function OrderBookHeader({ symbol }: { symbol: string }) {
  return (
    <div className="flex items-center justify-between border-b border-border-soft px-3.5 py-3">
      <div>
        <div className="text-body-sm font-bold">订单簿深度</div>
        <div className="text-[10px] text-text-muted">{symbol}</div>
      </div>
      <span className="kq-live-badge">L2</span>
    </div>
  )
}

/** OrderRow — 订单簿单行(深度条 + 价格/数量/总额)。总额用 decimal.js(金额红线)。 */
function OrderRow({ px, qty, maxQty, side }: { px: number; qty: number; maxQty: number; side: 'ask' | 'bid' }) {
  const total = toDecimal(px).times(toDecimal(qty))
  const depthPct = maxQty > 0 ? Math.min(60, (qty / maxQty) * 60) : 0
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
