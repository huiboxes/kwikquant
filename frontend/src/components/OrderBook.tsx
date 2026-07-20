import { useMemo, useState } from 'react'
import { ChevronRight } from 'lucide-react'
import { Card } from '@/components/ui/card'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { ScrollArea } from '@/components/ui/scroll-area'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlTextClass } from '@/lib/pnl'

/**
 * OrderBook — 订单簿深度(共享组件,MarketPage 真数据 / TradingPage mock 数据共用)。
 *
 * 视觉照原型 done-design/components/{MarketPage,TradingPage}.jsx line 86-128 / 137-182 抄:
 *  - Card + Header(symbol + L2 徽章)+ 列标题(价格/数量/总额 grid 3 col)
 *  - asks(卖)8 档 + 中间价区(买一/卖一 | 最新价 ▶ | 点差)+ bids(买)8 档
 *  - 深度条:qty/maxQty*60% width,asks 右起红 / bids 左起绿
 *
 * 新增交互(用户 2026-07-19 要求,prototype 无 → 不算偏离 prototype 视觉,仅扩展交互):
 *  - 默认买卖盘只展示 8 档
 *  - 中间价区是 button,右侧 ▶ 箭头,点击开 Sheet 右抽屉看完整订单簿(全档,ScrollArea 纵滚)
 *
 * 颜色统一交易所标准:ask=红(text-down,卖)/ bid=绿(text-up,买)。
 *   注:原型 MarketPage.jsx ask 绿 bid 红(反的),TradingPage.jsx ask 红 bid 绿(对的)——两原型不一致。
 *   本组件统一取交易所标准(ask 红 bid 绿),修 MarketPage 现状反色(记 TD)。
 *
 * 金额红线:价格(price/last/spread)展示 formatMoney(toDecimal,...) decimal.js;
 *  总额 = toDecimal(px).times(toDecimal(qty))(qty 是数量 number,转 decimal 参与运算不违反红线)。
 *  全 kq-mono-row 等宽对齐。
 * a11y:中间价 button aria-label;Sheet radix 自带焦点陷阱 + Esc 关闭;ChevronRight aria-hidden。
 */
export type OrderBookLevel = { price: number; qty: number }

/** 默认买卖盘各展示档数(完整档在 Sheet 抽屉里看)。 */
const VISIBLE_DEPTH = 8

export function OrderBook({
  symbol,
  asks,
  bids,
  last,
  pct,
  loading = false,
  error = false,
  badge = 'L2',
}: {
  symbol: string
  asks: OrderBookLevel[]
  bids: OrderBookLevel[]
  /** 最新价(REST 快照;MarketPage 传 selTicker.last,TradingPage mock 传 61220.5)。 */
  last: number
  /** 24h 涨跌%(决定最新价涨跌色)。 */
  pct: number
  loading?: boolean
  error?: boolean
  /** 右上徽章(L2 / PERP)。 */
  badge?: string
}) {
  const [open, setOpen] = useState(false)

  const { asks6, bids6, maxQty6, maxQtyAll } = useMemo(() => {
    const a6 = asks.slice(0, VISIBLE_DEPTH)
    const b6 = bids.slice(0, VISIBLE_DEPTH)
    const max6 = Math.max(0, ...a6.map((l) => l.qty), ...b6.map((l) => l.qty))
    const maxAll = Math.max(0, ...asks.map((l) => l.qty), ...bids.map((l) => l.qty))
    return { asks6: a6, bids6: b6, maxQty6: max6, maxQtyAll: maxAll }
  }, [asks, bids])

  const spread = asks[0] && bids[0] ? Math.abs(asks[0].price - bids[0].price) : null
  const dp = last < 1 ? 4 : 2

  if (loading) {
    return (
      <Card className="flex flex-col p-0">
        <OrderBookHeader symbol={symbol} badge={badge} />
        <div className="px-3.5 py-6">
          <LoadingState />
        </div>
      </Card>
    )
  }
  if (error) {
    return (
      <Card className="flex flex-col p-0">
        <OrderBookHeader symbol={symbol} badge={badge} />
        <div className="px-3.5 py-6">
          <ErrorState />
        </div>
      </Card>
    )
  }

  return (
    <Card className="flex flex-col p-0">
      <OrderBookHeader symbol={symbol} badge={badge} />
      <div className="grid grid-cols-3 px-3.5 pb-1 pt-1.5 text-[9px] uppercase tracking-[0.06em] text-text-muted">
        <span>价格</span>
        <span className="text-right">数量</span>
        <span className="text-right">总额</span>
      </div>
      {/* asks(卖)— 默认 6 档,红 */}
      <div className="px-3.5 pb-2 text-[11px]">
        {asks6.map((r, i) => (
          <OrderRow key={'a' + i} px={r.price} qty={r.qty} maxQty={maxQty6} side="ask" dp={dp} />
        ))}
      </div>
      {/* 中间价区:button 整体可点开 Sheet。最新价 + ▶ 箭头(右边)。 */}
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="查看完整订单簿"
        className="flex w-full items-center justify-between border-y border-border-soft bg-surface-card-2 px-3.5 py-2 text-left transition-colors hover:bg-surface-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <span className="text-[11px] text-text-muted">买一 / 卖一</span>
        <span className="flex items-center gap-1">
          <span className={`kq-mono-row text-body-sm font-bold ${pnlTextClass(pct)}`}>
            {formatMoney(toDecimal(last), { dp })}
          </span>
          <ChevronRight className="size-3.5 text-text-muted" aria-hidden />
        </span>
        <span className="text-[10px] text-text-muted">
          点差 {spread != null ? formatMoney(toDecimal(spread), { dp }) : '—'}
        </span>
      </button>
      {/* bids(买)— 默认 6 档,绿 */}
      <div className="px-3.5 py-2 text-[11px]">
        {bids6.map((r, i) => (
          <OrderRow key={'b' + i} px={r.price} qty={r.qty} maxQty={maxQty6} side="bid" dp={dp} />
        ))}
      </div>

      <FullOrderBookSheet
        open={open}
        onOpenChange={setOpen}
        symbol={symbol}
        asks={asks}
        bids={bids}
        last={last}
        pct={pct}
        spread={spread}
        dp={dp}
        maxQty={maxQtyAll}
      />
    </Card>
  )
}

/** OrderBookHeader — 标题 + symbol + 徽章(loading/error/normal 三态复用)。 */
function OrderBookHeader({ symbol, badge }: { symbol: string; badge: string }) {
  return (
    <div className="flex items-center justify-between border-b border-border-soft px-3.5 py-3">
      <div>
        <div className="text-body-sm font-bold">订单簿深度</div>
        <div className="text-[10px] text-text-muted">{symbol}</div>
      </div>
      <span className="kq-live-badge">{badge}</span>
    </div>
  )
}

/** OrderRow — 订单簿单行(深度条 + 价格/数量/总额)。总额用 decimal.js(金额红线)。 */
function OrderRow({
  px,
  qty,
  maxQty,
  side,
  dp,
}: {
  px: number
  qty: number
  maxQty: number
  side: 'ask' | 'bid'
  dp: number
}) {
  const total = toDecimal(px).times(toDecimal(qty))
  const depthPct = maxQty > 0 ? Math.min(60, (qty / maxQty) * 60) : 0
  return (
    <div className="kq-mono-row relative grid grid-cols-3 py-[3px]">
      <span className={`relative z-10 ${side === 'ask' ? 'text-down' : 'text-up'}`}>
        {formatMoney(toDecimal(px), { dp })}
      </span>
      <span className="relative z-10 text-right">{formatMoney(toDecimal(qty), { dp: 4 })}</span>
      <span className="relative z-10 text-right text-text-muted">{formatMoney(total, { dp: 0 })}</span>
      <span
        className={`absolute top-1 bottom-1 ${side === 'ask' ? 'right-0' : 'left-0'} z-0 rounded`}
        style={{
          width: `${depthPct}%`,
          background:
            side === 'ask'
              ? 'linear-gradient(90deg,transparent,color-mix(in oklab, var(--down) 10%, transparent))'
              : 'linear-gradient(270deg,transparent,color-mix(in oklab, var(--up) 10%, transparent))',
          pointerEvents: 'none',
        }}
      />
    </div>
  )
}

/** FullOrderBookSheet — 右侧抽屉,完整档(asks 全 + 中间 + bids 全),ScrollArea 纵滚。 */
function FullOrderBookSheet({
  open,
  onOpenChange,
  symbol,
  asks,
  bids,
  last,
  pct,
  spread,
  dp,
  maxQty,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  symbol: string
  asks: OrderBookLevel[]
  bids: OrderBookLevel[]
  last: number
  pct: number
  spread: number | null
  dp: number
  maxQty: number
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 p-0 sm:max-w-[360px]">
        <SheetHeader className="border-b border-border-soft px-3.5 py-3 text-left">
          <SheetTitle className="text-body-sm font-bold">订单簿深度 · 完整</SheetTitle>
          <SheetDescription className="sr-only">
            {symbol} 完整订单簿,买卖盘全档,可纵向滚动
          </SheetDescription>
          <div className="text-[11px] text-text-muted">{symbol}</div>
        </SheetHeader>
        <div className="grid grid-cols-3 px-3.5 pb-1 pt-1.5 text-[9px] uppercase tracking-[0.06em] text-text-muted">
          <span>价格</span>
          <span className="text-right">数量</span>
          <span className="text-right">总额</span>
        </div>
        <ScrollArea className="min-h-0 flex-1">
          {/* asks(卖)全档,红 */}
          <div className="px-3.5 pb-2 text-[11px]">
            {asks.map((r, i) => (
              <OrderRow key={'a' + i} px={r.price} qty={r.qty} maxQty={maxQty} side="ask" dp={dp} />
            ))}
          </div>
          {/* 中间价区(静态展示,不开嵌套 Sheet) */}
          <div className="flex items-center justify-between border-y border-border-soft bg-surface-card-2 px-3.5 py-2">
            <span className="text-[11px] text-text-muted">买一 / 卖一</span>
            <span className={`kq-mono-row text-body-sm font-bold ${pnlTextClass(pct)}`}>
              {formatMoney(toDecimal(last), { dp })}
            </span>
            <span className="text-[10px] text-text-muted">
              点差 {spread != null ? formatMoney(toDecimal(spread), { dp }) : '—'}
            </span>
          </div>
          {/* bids(买)全档,绿 */}
          <div className="px-3.5 py-2 text-[11px]">
            {bids.map((r, i) => (
              <OrderRow key={'b' + i} px={r.price} qty={r.qty} maxQty={maxQty} side="bid" dp={dp} />
            ))}
          </div>
        </ScrollArea>
      </SheetContent>
    </Sheet>
  )
}
