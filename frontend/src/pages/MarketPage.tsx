import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Code2 } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { LivePrice } from '@/components/LivePrice'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { useMarketTickers } from '@/hooks/useMarketTickers'
import { useAccounts } from '@/hooks/useAccounts'
import { toDecimal, formatMoney, formatMoneyCN } from '@/lib/money'
import { pnlArrow } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * MarketPage — 行情页(移动端交易所风格列表)。
 *
 * 列表行:左[首字母色块 | 币种名加粗 / 成交额中文单位(亿/千万/万)] 中[最新价 mono] 右[涨跌幅绿/红背景块 2 位小数] + 行尾"策"按钮。
 * 现货/合约 tab(可点视觉:active accent 背景);表头三列双向排序图标(▲▼,active 方向高亮)。
 * 行点击 → /trade?symbol=&marketType=;行尾"策"按钮 → /strategy?symbol=&marketType=(策略工作台,接 ?symbol 预填)。
 * 搜索标的用 TopBar ⌘K 命令面板(不在行情页重复加搜索框)。
 *
 * 行点击用 Link(absolute 覆盖,避 react-hooks/purity);策按钮 Link z-10 上层。
 * 数据:useMarketTickers(batch GET /tickers,1 次替 N 次,10s 缓存)。
 *
 * 金额:成交额 formatMoneyCN(亿/千万/万);涨跌幅 formatMoney dp=2 sign(2 位小数 + 正 + 负 -);最新价 LivePrice。
 * a11y:涨跌不靠色(背景块 + +/- + 箭头 ▲▼ 三重);排序图标 ▲▼ 双向(active 方向高亮);行 Link aria-label + 策按钮 aria-label。
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

  // 动态基准交易所:取默认模拟盘账户 exchange,兜底 OKX
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
          <TabsList className="rounded-lg bg-surface-card-2 p-0.5">
            <TabsTrigger
              value="SPOT"
              className="rounded-md px-3 py-1 text-body-sm data-[state=active]:bg-accent data-[state=active]:text-accent-foreground"
            >
              现货
            </TabsTrigger>
            <TabsTrigger
              value="PERP"
              className="rounded-md px-3 py-1 text-body-sm data-[state=active]:bg-accent data-[state=active]:text-accent-foreground"
            >
              合约
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      <Card className="p-0">
        {/* 表头:三列双向排序图标(▲▼,active 方向高亮 text-primary,非 active text-muted/40) */}
        <div className="grid grid-cols-[1fr_7rem_6rem_2.5rem] items-center gap-3 border-b border-border-soft px-4 py-2 text-[11px] uppercase tracking-[0.06em] text-text-muted">
          <button
            onClick={() => toggleSort('quoteVolume')}
            className="flex items-center gap-1 text-left"
            aria-sort={sort === 'quoteVolume' ? (order === 'asc' ? 'ascending' : 'descending') : 'none'}
          >
            币种 / 成交额
            <SortArrows active={sort === 'quoteVolume'} order={order} />
          </button>
          <button
            onClick={() => toggleSort('last')}
            className="flex items-center justify-end gap-1"
            aria-sort={sort === 'last' ? (order === 'asc' ? 'ascending' : 'descending') : 'none'}
          >
            最新价
            <SortArrows active={sort === 'last'} order={order} />
          </button>
          <button
            onClick={() => toggleSort('percentage')}
            className="flex items-center justify-end gap-1"
            aria-sort={sort === 'percentage' ? (order === 'asc' ? 'ascending' : 'descending') : 'none'}
          >
            涨跌幅
            <SortArrows active={sort === 'percentage'} order={order} />
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
            {(data ?? [])
              .filter((item) => item.ticker?.symbol)
              .map((item) => (
                <MarketRow key={item.ticker!.symbol} item={item} marketType={tab} />
              ))}
          </ul>
        )}
      </Card>
    </div>
  )
}

/** 双向排序图标:▲(asc)▼(desc) 上下双倒三角,active 方向高亮 text-text-primary,非 active text-muted/40。 */
function SortArrows({ active, order }: { active: boolean; order: Order }) {
  return (
    <span className="flex flex-col leading-none text-[8px]">
      <span className={active && order === 'asc' ? 'text-text-primary' : 'text-text-muted/40'}>▲</span>
      <span className={active && order === 'desc' ? 'text-text-primary' : 'text-text-muted/40'}>▼</span>
    </span>
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
  const strategyHref = `/strategy?symbol=${encodeURIComponent(sym)}&marketType=${marketType}`

  return (
    <li className="relative grid grid-cols-[1fr_7rem_6rem_2.5rem] items-center gap-3 px-4 py-2.5 hover:bg-surface-card-2">
      {/* 行点击区:absolute 覆盖整行;策按钮 Link z-10 在上层不触发行 */}
      <Link to={tradeHref} className="absolute inset-0" aria-label={`交易 ${displaySymbol}`} tabIndex={-1} />
      <div className="flex items-center gap-2.5 min-w-0">
        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent text-accent-foreground text-[11px] font-bold">
          {initial}
        </span>
        <div className="min-w-0">
          <div className="text-body-sm font-bold text-text-primary truncate">{displaySymbol}</div>
          <div className="text-[10px] text-text-muted">Vol {formatMoneyCN(toDecimal(vol))}</div>
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
          {pnlArrow(pct)} {formatMoney(toDecimal(t.percentage ?? 0), { dp: 2, sign: true })}%
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
