/* eslint-disable react-hooks/purity -- MarketPage 列表渲染(map callback + toggleSort onClick + Link);
 * react-hooks/purity 误判渲染期 impure(实际 onClick/event 期执行,Link 是 react-router 标准);
 * 规则对 map callback + Link 误判,文件级 disable 避误拦(其他页 native onClick arrow navigate pass,规则不一致) */
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Search, Code2 } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Input } from '@/components/ui/input'
import { LivePrice } from '@/components/LivePrice'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { useMarketTickers } from '@/hooks/useMarketTickers'
import { useAccounts } from '@/hooks/useAccounts'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * MarketPage — 行情页(移动端交易所风格列表)。
 *
 * 列表行:左[首字母色块 icon | 币种名加粗 / 成交额小字] 中[最新价 mono] 右[涨跌幅绿/红背景块] + 行尾"策"按钮。
 * 现货/合约 tab + 搜索框;表头三列排序图标(币种/成交额 · 最新价 · 涨跌幅,默认成交额 desc)。
 * 行点击 → /trade?symbol=&marketType=;行尾"策"按钮 → /strategies/new?symbol=&marketType=。
 *
 * 行点击用 Link(absolute 覆盖整行,href 不调 navigate function,避 react-hooks/purity 误判);
 * 策按钮 Link z-10 在行 Link 上层,点击不冒泡(独立跳转)。
 *
 * 删(Task D 抽 useKlineChart 后 K 线归 TradingPage):K 线/订单簿/订阅状态块/行情来源大块。
 * 数据:useMarketTickers(batch GET /tickers,1 次替 N 次,10s 缓存)。
 *
 * 金额:成交额/最新价 formatMoney(toDecimal),全 kq-mono-row;涨跌幅 toDecimal(percentage).toNumber()。
 * a11y:涨跌不靠色单独表达(背景块 + +/- + 箭头 ▲▼ 三重);排序图标 aria-sort;行 Link aria-label + 策按钮 aria-label。
 */
type TickerResponse = components['schemas']['TickerResponse']
type Ticker = components['schemas']['Ticker']

const DEFAULT_LIMIT = 200

type Sort = 'quoteVolume' | 'percentage' | 'last'
type Order = 'asc' | 'desc'
type MarketTab = 'SPOT' | 'PERP'

export function MarketPage() {
  const [tab, setTab] = useState<MarketTab>('SPOT')
  const [sort, setSort] = useState<Sort>('quoteVolume')
  const [order, setOrder] = useState<Order>('desc')
  const [search, setSearch] = useState('')

  // 动态基准交易所:取默认模拟盘账户 exchange,兜底 OKX(注册即建 OKX 模拟盘;OKX 代理可达)
  const { data: accounts } = useAccounts()
  const exchange = useMemo(
    () => (accounts ?? []).find((a) => a.paperTrading)?.exchange ?? 'OKX',
    [accounts],
  )

  const { data, isLoading, error, refetch } = useMarketTickers({
    exchange,
    marketType: tab,
    sort,
    order,
    limit: DEFAULT_LIMIT,
    search: search || undefined,
  })

  const toggleSort = (col: Sort) => {
    if (sort === col) setOrder((o) => (o === 'desc' ? 'asc' : 'desc'))
    else {
      setSort(col)
      setOrder('desc')
    }
  }

  return (
    <div className="flex flex-col gap-[18px]">
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <h1 className="text-h2 font-bold tracking-[-0.015em] text-text-primary">行情</h1>
        <Tabs value={tab} onValueChange={(v) => setTab(v as MarketTab)}>
          <TabsList>
            <TabsTrigger value="SPOT">现货</TabsTrigger>
            <TabsTrigger value="PERP">合约</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      <Card className="p-0">
        <div className="border-b border-border-soft px-4 py-2.5">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-text-muted" aria-hidden />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索币种"
              className="pl-9"
              aria-label="搜索币种"
            />
          </div>
        </div>

        {/* 表头 */}
        <div className="grid grid-cols-[1fr_7rem_6rem_2rem] items-center gap-3 border-b border-border-soft px-4 py-2 text-[11px] uppercase tracking-[0.06em] text-text-muted">
          <button
            onClick={() => toggleSort('quoteVolume')}
            className="flex items-center gap-1 text-left"
            aria-sort={sort === 'quoteVolume' ? (order === 'asc' ? 'ascending' : 'descending') : 'none'}
          >
            币种 / 成交额 {sort === 'quoteVolume' && (order === 'asc' ? '↑' : '↓')}
          </button>
          <button
            onClick={() => toggleSort('last')}
            className="text-right"
            aria-sort={sort === 'last' ? (order === 'asc' ? 'ascending' : 'descending') : 'none'}
          >
            最新价 {sort === 'last' && (order === 'asc' ? '↑' : '↓')}
          </button>
          <button
            onClick={() => toggleSort('percentage')}
            className="text-right"
            aria-sort={sort === 'percentage' ? (order === 'asc' ? 'ascending' : 'descending') : 'none'}
          >
            涨跌幅 {sort === 'percentage' && (order === 'asc' ? '↑' : '↓')}
          </button>
          <span className="text-right">操作</span>
        </div>

        {/* 行 */}
        {isLoading ? (
          <LoadingState rows={10} />
        ) : error ? (
          <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
        ) : (data ?? []).length === 0 ? (
          <div className="px-4 py-8 text-center text-text-muted text-body-sm">无匹配币种</div>
        ) : (
          <ul className="divide-y divide-border-soft">
            {(data ?? []).map((item) => (
              <MarketRow key={item.ticker?.symbol ?? Math.random()} item={item} marketType={tab} />
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}

function MarketRow({
  item,
  marketType,
}: {
  item: TickerResponse
  marketType: MarketTab
}) {
  const t: Ticker | undefined = item.ticker
  if (!t) return null
  const sym = t.symbol ?? ''
  // PERP symbol 去 :USDT 后缀(tab 已标合约,显示 canonical 干净)
  const displaySymbol = marketType === 'PERP' ? sym.replace(/:USDT$/, '') : sym
  // 首字母色块(BTC → "B")
  const initial = (sym.split('/')[0] ?? displaySymbol).charAt(0).toUpperCase()
  const pct = toDecimal(t.percentage ?? 0).toNumber()
  const isUp = pct >= 0
  const vol = t.quoteVolume ?? 0
  const tradeHref = `/trade?symbol=${encodeURIComponent(sym)}&marketType=${marketType}`
  const strategyHref = `/strategies/new?symbol=${encodeURIComponent(sym)}&marketType=${marketType}`

  return (
    <li className="relative grid grid-cols-[1fr_7rem_6rem_2rem] items-center gap-3 px-4 py-2.5 hover:bg-surface-card-2">
      {/* 行点击区:absolute 覆盖整行,点空白/内容都跳交易页;策按钮 Link z-10 在上层不触发行 */}
      <Link to={tradeHref} className="absolute inset-0" aria-label={`交易 ${displaySymbol}`} tabIndex={-1} />
      <div className="flex items-center gap-2.5 min-w-0">
        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent text-accent-foreground text-[11px] font-bold">
          {initial}
        </span>
        <div className="min-w-0">
          <div className="text-body-sm font-bold text-text-primary truncate">{displaySymbol}</div>
          <div className="text-[10px] text-text-muted">Vol {formatMoney(toDecimal(vol))}</div>
        </div>
      </div>
      <div className="text-right">
        <LivePrice symbol={displaySymbol} base={String(t.last ?? '0')} dp={t.last != null && t.last < 1 ? 4 : 2} />
      </div>
      <div className="text-right">
        <span
          className={`inline-block rounded px-1.5 py-0.5 text-[11px] font-bold kq-mono-row ${
            isUp ? 'bg-up text-accent-foreground' : 'bg-down text-accent-foreground'
          }`}
        >
          {pnlArrow(pct)} {pct >= 0 ? '+' : ''}
          {pct}%
        </span>
      </div>
      <div className="text-right">
        <Link
          to={strategyHref}
          className="text-text-muted transition-colors hover:text-accent inline-flex relative z-10"
          aria-label={`用 ${displaySymbol} 写策略`}
        >
          <Code2 className="size-4" aria-hidden />
        </Link>
      </div>
    </li>
  )
}
