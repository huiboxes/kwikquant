import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Code2, ChevronUp, ChevronDown } from 'lucide-react'
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
 * 现货/合约 tab(active accent 背景可点);表头三列双向排序图标(ChevronUp/Down 同尺寸,active 方向高亮)。
 * 行点击 → /trade?symbol=&marketType=;行尾"策"按钮 → /strategy?symbol=&marketType=(StrategyPage 自动弹"创建新策略"dialog 预填)。
 * 搜索标的用 TopBar ⌘K 命令面板(不在行情页重复加搜索框)。
 *
 * 排序**前端本地 sort**(即时,点列头 sort/order state 变 → sortedData useMemo 重算,不 re-fetch)。
 * 数据:useMarketTickers(batch GET /tickers 全量,10s 缓存;切 tab 显示 loading 而非旧数据)。
 *
 * 金额:成交额 formatMoneyCN(亿/千万/万);涨跌幅 formatMoney dp=2 sign(2 位 + 正 + 负 -);最新价 LivePrice。
 * a11y:涨跌不靠色(背景块 + +/- + 箭头 ▲▼ 三重);排序图标 aria-sort;行 Link aria-label + 策按钮 aria-label。
 */
type TickerResponse = components['schemas']['TickerResponse']
type Ticker = components['schemas']['Ticker']

const DEFAULT_LIMIT = 200

type Sort = 'quoteVolume' | 'percentage' | 'last'
type Order = 'asc' | 'desc'
type MarketTab = 'SPOT' | 'PERP'

/** 取 ticker 排序字段(Decimal,前端本地 sort)。 */
function sortField(t: Ticker | undefined, sort: Sort) {
  if (!t) return toDecimal(0)
  if (sort === 'quoteVolume') return toDecimal(t.quoteVolume)
  if (sort === 'percentage') return toDecimal(t.percentage)
  return toDecimal(t.last)
}

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

  // 后端拿全量(默认 quoteVolume desc),前端本地 sort(即时,点列头不 re-fetch)
  const { data, isLoading, error, refetch } = useMarketTickers({
    exchange,
    marketType: tab,
    limit: DEFAULT_LIMIT,
  })

  const sortedData = useMemo(() => {
    const arr = [...(data ?? [])]
    arr.sort((a, b) => {
      const av = sortField(a.ticker, sort)
      const bv = sortField(b.ticker, sort)
      // Decimal 比较 → toNumber 返 sign(<0/0/>0),comparator 不展示不存储,精度 OK
      return order === 'asc' ? av.minus(bv).toNumber() : bv.minus(av).toNumber()
    })
    return arr
  }, [data, sort, order])

  // 3 态排序:点列头 desc → asc → 回默认(成交额 desc);默认即 quoteVolume desc
  const toggleSort = (col: Sort) => {
    if (sort !== col) {
      setSort(col)
      setOrder('desc')
    } else if (order === 'desc') {
      setOrder('asc')
    } else {
      setSort('quoteVolume')
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
        {/* 表头:三列双向排序图标(ChevronUp/Down 同尺寸,active 方向高亮 text-primary) */}
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
        ) : sortedData.length === 0 ? (
          <div className="px-4 py-8 text-center text-text-muted text-body-sm">无匹配币种</div>
        ) : (
          <ul className="divide-y divide-border-soft">
            {sortedData
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

/** 双向排序图标:ChevronUp(asc)/ChevronDown(desc)同尺寸 size-2.5,active 方向高亮 text-text-primary。 */
function SortArrows({ active, order }: { active: boolean; order: Order }) {
  return (
    <span className="flex flex-col leading-none">
      <ChevronUp
        className={`size-2.5 ${active && order === 'asc' ? 'text-text-primary' : 'text-text-muted/40'}`}
        aria-hidden
      />
      <ChevronDown
        className={`size-2.5 -mt-1 ${active && order === 'desc' ? 'text-text-primary' : 'text-text-muted/40'}`}
        aria-hidden
      />
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
      {/* 行点击区:absolute 覆盖前 3 列(右留 2.5rem 策略列),策略 Link 独立不重叠,点 Code2 边缘也不误触行 */}
      <Link to={tradeHref} className="absolute inset-y-0 left-0 right-[2.5rem]" aria-label={`交易 ${displaySymbol}`} tabIndex={-1} />
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
