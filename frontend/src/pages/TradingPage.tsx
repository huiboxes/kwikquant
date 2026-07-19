import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { RotateCcw, AlertTriangle } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { SectionTitle } from '@/components/SectionTitle'
import { Chip } from '@/components/Chip'
import { OrderStatusBadge } from '@/components/OrderStatusBadge'
import { OrderBook } from '@/components/OrderBook'
import { Ticker } from '@/components/Ticker'
import { KlineChart, type KlineCandle } from '@/components/charts/KlineChart'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { useUiStore, type TradeMode } from '@/stores/uiStore'
import { useAccounts, useAccountBalance, useResetPaperAccount } from '@/hooks/useAccounts'
import { useOrders, usePositions, useSubmitOrder, useClosePosition } from '@/hooks/useTrading'
import {
  normalizeOrderStatus,
  sideLabel,
  orderTypeLabel,
  type OrderSubmitRequest,
} from '@/api/order'
import type { components } from '@/types/api-gen'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import { sumUnrealizedPnl } from '@/lib/positionPnl'
import { ApiError } from '@/lib/http'

/**
 * TradingPage — 交易页(照原型 done-design/components/TradingPage.jsx port)。
 *
 * 适配后端契约(honest 差异,不静默照做,TD-039~047):
 *  - TD-039:OrderDetailDto.status 6 态(NEW|PARTIAL|FILLED|CANCELLED|REJECTED|EXPIRED)
 *    → OrderStatusBadge 9 态 ws 命名(normalizeOrderStatus 映射 PARTIAL→PARTIALLY_FILLED 等)。
 *  - TD-040:PositionDto.unrealizedPnl/currentPrice(行情不可用 null)→ uPnl 列用真实字段,null 显 —;BalanceBar 单账户 uPnl = sumUnrealizedPnl(positions)。
 *  - TD-041:风控拒 POST /orders 200+code=4105(非 HTTP 错误)→ useSubmitOrder onError
 *    检查 ApiError.code===4105 → toast.error(reason) + navigate('/risk')。
 *  - TD-042:marketType 固定 SPOT(原型无切换 UI)。TD-043:symbol 固定 BTC/USDT。
 *  - TD-044 已接:POST /positions/{id}/close 反向市价单平仓 → useClosePosition + ConfirmDialog(LIVE destructive)。
 *  - TD-045 已接:POST /accounts/{id}/paper/reset → useResetPaperAccount + AlertDialog(仅 PAPER,LIVE 拒 7001)。
 *  - TD-046:WS 推送已接(useTradingEvents 全局订阅 /topic/orders + /topic/fills +
 *    /topic/positions + /topic/portfolio,收到 invalidate 对应 queryKeys,各页自动刷新)。
 *  - TD-047:K线 静态 mock(接真实 useKlines 留账);OrderBook 静态 mock(TD-009 留账,依赖 TD-012 PAPER 同源行情)。
 *
 * PAPER/LIVE 强区分(多层防护,用户绝不误把实盘当模拟):
 *  - banner 配色(PAPER up 色 / LIVE accent 色)+ 文案(虚拟 10 万 vs 真金白银)
 *  - sticky LIVE 徽章(fixed top-right,kqPulse 动画)
 *  - 切 LIVE Dialog(liveConfirmedThisSession 会话级 flag,本会话不再重复)
 *  - LIVE 下单 Dialog + Checkbox(必须勾选"知悉风险")
 *  - 平仓/重置 destructive Confirm/AlertDialog
 *
 * 金额:free/used/total/qty/avg/realizedPnl/notional/fee 全 toDecimal + formatMoney,
 * notional = qty × price(decimal.js .times),fee = notional × 0.0004。展示全 kq-mono-row。
 * 涨跌(买卖/LONG/SHORT/realizedPnl)用 pnlArrow + pnlTextClass + 文本标签(a11y)。
 * 图标 lucide-react,不用 emoji。
 */
type PositionDto = components['schemas']['PositionDto']
type OrderDetailDto = components['schemas']['OrderDetailDto']
type ExchangeAccountView = components['schemas']['ExchangeAccountView']

const SYMBOL = 'BTC/USDT'
const MARKET_TYPE = 'SPOT'
const ORDER_TYPES = [
  'LIMIT',
  'MARKET',
  'STOP',
  'STOP_LIMIT',
  'TAKE_PROFIT_MARKET',
  'TAKE_PROFIT_LIMIT',
  'TRAILING_STOP',
] as const
const MARKET_LIKE: readonly string[] = ['MARKET', 'STOP', 'TAKE_PROFIT_MARKET', 'TRAILING_STOP']
const TIF = ['GTC', 'IOC', 'FOK', 'GTD'] as const
const KTIFS = ['1m', '5m', '15m', '1h', '4h', '1d'] as const

/** 静态 mock K 线(TD-047,接真实 useKlines 留账)。60 根 BTC/USDT 15m。 */
const CANDLES: KlineCandle[] = Array.from({ length: 60 }, (_, i) => {
  const base = 60000 + Math.sin(i * 0.4) * 2000 + i * 30
  const hh = 8 + Math.floor(i / 12)
  const mm = (i * 5) % 60
  return {
    ts: `2026-07-04T${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00Z`,
    o: base,
    h: base + 150,
    l: base - 120,
    c: base + 60,
    v: 1000 + i * 5,
  }
})

export function TradingPage() {
  const navigate = useNavigate()
  const tradeMode = useUiStore((s) => s.tradeMode)
  const setTradeMode = useUiStore((s) => s.setTradeMode)
  const liveConfirmedThisSession = useUiStore((s) => s.liveConfirmedThisSession)
  const setLiveConfirmedThisSession = useUiStore((s) => s.setLiveConfirmedThisSession)

  const isLive = tradeMode === 'LIVE'
  const [showLiveConfirm, setShowLiveConfirm] = useState(false)
  const [closeTarget, setCloseTarget] = useState<PositionDto | null>(null)
  const [showReset, setShowReset] = useState(false)
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null)

  const { data: accounts, isLoading, error, refetch } = useAccounts()
  // 当前 mode 匹配的账户列表(PAPER → paperTrading true,LIVE → false)
  const modeAccounts = useMemo(
    () => (accounts ?? []).filter((a) => a.paperTrading !== isLive),
    [accounts, isLive],
  )
  // derived 账户 id(避免 set-state-in-effect:mode 切换时 selectedAccountId 失效自动回退首个)
  const effectiveAccountId =
    selectedAccountId != null && modeAccounts.some((a) => a.id === selectedAccountId)
      ? selectedAccountId
      : (modeAccounts[0]?.id ?? null)

  // TD-044 平仓 / TD-045 重置 PAPER mutation(后端端点已就绪,接 ConfirmDialog/AlertDialog)
  const closeMut = useClosePosition()
  const resetMut = useResetPaperAccount()

  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
  }
  if (isLoading) return <LoadingState />

  const switchMode = (target: TradeMode) => {
    if (target === 'LIVE' && tradeMode === 'PAPER') {
      if (liveConfirmedThisSession) setTradeMode('LIVE')
      else setShowLiveConfirm(true)
    } else {
      setTradeMode(target)
    }
  }
  const confirmLive = () => {
    setLiveConfirmedThisSession(true)
    setTradeMode('LIVE')
    setShowLiveConfirm(false)
    toast.success('已切到 LIVE 实盘', { description: '本会话内不再重复确认' })
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Mode switcher banner */}
      <div
        className="rounded-lg border p-5 transition-all duration-300"
        style={{
          background: isLive
            ? 'linear-gradient(135deg, var(--accent-soft) 0%, var(--surface-card) 100%)'
            : 'linear-gradient(135deg, color-mix(in oklab, var(--up) 10%, transparent) 0%, var(--surface-card) 100%)',
          borderColor: isLive ? 'var(--accent)' : 'var(--up)',
        }}
      >
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3.5">
          <div className="flex items-center gap-2.5">
            {isLive ? (
              <span className="kq-live-badge px-2.5 py-1 text-caption">● LIVE · 实盘</span>
            ) : (
              <span className="kq-paper-badge px-2.5 py-1 text-caption">PAPER · 模拟</span>
            )}
            <div>
              <div className="text-[18px] font-bold tracking-[-0.01em] text-text-primary">
                {isLive ? '实盘交易' : '模拟盘交易'}
              </div>
              <div className="mt-0.5 text-caption text-text-muted">
                {isLive
                  ? '真金白银 · 余额由交易所维护 · 不可重置'
                  : '虚拟 10 万 USDT · 基准行情撮合 · 可重置'}
              </div>
            </div>
          </div>
          {!isLive && (
            <Button variant="ghost" size="sm" onClick={() => setShowReset(true)}>
              <RotateCcw className="size-4" aria-hidden />
              重置模拟盘
            </Button>
          )}
        </div>
        <div className="flex gap-2">
          <SegMode
            value="PAPER"
            label="PAPER · 模拟"
            sub="10 万 USDT 虚拟"
            tone="PAPER"
            active={!isLive}
            onClick={() => switchMode('PAPER')}
          />
          <SegMode
            value="LIVE"
            label="LIVE · 实盘"
            sub="真金白银"
            tone="LIVE"
            active={isLive}
            onClick={() => switchMode('LIVE')}
          />
        </div>
      </div>

      {/* Sticky LIVE badge — fixed below topbar */}
      {isLive && (
        <div className="pointer-events-none fixed right-[18px] top-[70px] z-[90]">
          <span
            className="kq-live-badge kq-pulse px-2.5 py-1 text-caption"
          >
            ● LIVE
          </span>
        </div>
      )}

      {/* Balance */}
      <BalanceBar accountId={effectiveAccountId} isLive={isLive} />

      {/* Main 3-col */}
      <div className="-mx-2 overflow-x-auto px-2">
        <div className="kq-trade-grid grid gap-[18px]" style={{ gridTemplateColumns: '1.4fr 320px 1fr', minWidth: 960 }}>
          {/* Chart */}
          <Card className="overflow-hidden p-0">
            <div className="flex items-center justify-between border-b border-border-soft px-3.5 py-2.5">
              <strong className="text-body-sm font-bold text-text-primary">BTC/USDT · K 线</strong>
              <div className="flex gap-1.5">
                {KTIFS.map((t, i) => (
                  <button
                    key={t}
                    className={`rounded-md border px-2 py-1 text-caption transition-all ${i === 2 ? 'border-accent bg-accent-soft text-accent' : 'border-border-soft bg-surface-card-2 text-text-secondary'}`}
                    type="button"
                  >
                    {t}
                  </button>
                ))}
              </div>
            </div>
            <div className="overflow-auto p-2.5">
              <KlineChart data={CANDLES} height={300} />
            </div>
            <div className="flex gap-3.5 border-t border-border-soft px-3.5 py-2 text-caption text-text-muted">
              <span>
                O <Ticker base={60800} chg={0} dp={2} />
              </span>
              <span className="kq-mono-row text-up">H 62,150</span>
              <span className="kq-mono-row text-down">L 59,800</span>
              <span>
                C <Ticker base={61220} chg={2.34} dp={2} />
              </span>
              <span>
                Vol <span className="kq-mono-row">1.2B</span>
              </span>
            </div>
          </Card>
          {/* Order book — 共享 OrderBook 组件,TradingPage mock 数据(TD-009/012 留账:
              PAPER 同源行情未做前用确定性 mock,接真需 TD-012 定 PAPER orderbook 行为)。 */}
          <TradingOrderBook />
          {/* Order form */}
          <OrderForm
            isLive={isLive}
            accountId={effectiveAccountId}
            modeAccounts={modeAccounts}
            onAccountChange={setSelectedAccountId}
            onSubmitRiskReject={(reason) => {
              toast.error('风控拒绝', { description: reason })
              navigate('/risk')
            }}
          />
        </div>
      </div>

      {/* Positions + Orders */}
      <div className="kq-trade-bottom grid gap-[18px] md:grid-cols-2">
        <PositionsTable isLive={isLive} accountId={effectiveAccountId} onClose={setCloseTarget} />
        <OrdersTable accountId={effectiveAccountId} isLive={isLive} />
      </div>

      {/* 切到 LIVE 实盘 Dialog(照原型,会话级 flag) */}
      <Dialog open={showLiveConfirm} onOpenChange={setShowLiveConfirm}>
        <DialogContent className="max-w-[440px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="size-4 text-accent" aria-hidden />
              切到 LIVE 实盘
            </DialogTitle>
            <DialogDescription>
              LIVE 模式下下单使用真实交易所、真实资金、真实手续费。误操作可能造成实际亏损。
            </DialogDescription>
          </DialogHeader>
          <div className="rounded-lg border border-accent bg-accent-soft p-3.5 text-body-sm">
            <div className="font-bold text-accent">你正在切到实盘</div>
            <div className="mt-1 text-caption leading-relaxed text-accent">
              本次会话内不会再重复弹出确认。可随时通过顶栏切回 PAPER。
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowLiveConfirm(false)}>
              取消
            </Button>
            <Button onClick={confirmLive}>确认切到 LIVE</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 平仓 ConfirmDialog(TD-044 已接:POST /positions/{id}/close 反向市价单,LIVE destructive) */}
      <ConfirmDialog
        open={closeTarget != null}
        onOpenChange={(o) => {
          if (!o) setCloseTarget(null)
        }}
        title={isLive ? '确认实盘平仓' : '确认平仓'}
        description={`平掉 ${closeTarget?.symbol ?? ''} ${closeTarget?.side ?? ''} 持仓 ${closeTarget ? formatMoney(toDecimal(closeTarget.qty), { dp: 4 }) : ''}。以反向市价单平掉全部数量,走完整下单链路(风控+余额冻结)。`}
        confirmLabel={closeMut.isPending ? '平仓中…' : '平仓'}
        destructive={isLive}
        onConfirm={() => {
          if (!closeTarget || closeMut.isPending) return
          closeMut.mutate(
            { positionId: closeTarget.positionId, accountId: closeTarget.accountId },
            {
              onSuccess: () => {
                toast.success('平仓成功', { description: `${closeTarget?.symbol} 已平仓` })
                setCloseTarget(null)
              },
              onError: (e) => {
                toast.error('平仓失败', { description: (e as Error).message })
              },
            },
          )
        }}
      />

      {/* 重置 PAPER AlertDialog(TD-045 占位) */}
      <AlertDialog open={showReset} onOpenChange={setShowReset}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>重置 PAPER 模拟盘</AlertDialogTitle>
            <AlertDialogDescription>
              清订单 + 清仓 + 回 10 万 USDT 虚拟资金。仅 PAPER 模拟盘可重置(LIVE 账户后端拒 7001)。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-down text-on-accent hover:bg-down/90"
              onClick={() => {
                if (effectiveAccountId == null || resetMut.isPending) return
                resetMut.mutate(
                  { accountId: effectiveAccountId },
                  {
                    onSuccess: () => {
                      toast.success('PAPER 已重置', {
                        description: '持仓/订单已清,余额回 10 万 USDT',
                      })
                    },
                    onError: (e) => {
                      toast.error('重置失败', { description: (e as Error).message })
                    },
                  },
                )
              }}
            >
              {resetMut.isPending ? '重置中…' : '重置'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

/** SegMode — PAPER/LIVE 模式切换按钮。 */
function SegMode({
  value,
  label,
  sub,
  tone,
  active,
  onClick,
}: {
  value: TradeMode
  label: string
  sub: string
  tone: 'PAPER' | 'LIVE'
  active: boolean
  onClick: () => void
}) {
  const borderColor = active ? (tone === 'LIVE' ? 'var(--accent)' : 'var(--up)') : 'var(--border-soft)'
  const bg = active ? (tone === 'LIVE' ? 'var(--accent)' : 'var(--up)') : 'transparent'
  const fg = active ? 'var(--on-accent)' : tone === 'LIVE' ? 'var(--accent)' : 'var(--text-secondary)'
  return (
    <button
      type="button"
      onClick={onClick}
      className="kq-press flex flex-1 flex-col items-center gap-0.5 rounded-md border px-4 py-3 transition-all duration-150"
      style={{ borderColor, background: bg, color: fg, cursor: 'pointer' }}
      data-value={value}
    >
      <span className="text-body-sm font-bold">{label}</span>
      <span className="text-caption opacity-85">{sub}</span>
    </button>
  )
}

/** BalanceBar — 4 格:可用/冻结/总权益/未实现盈亏。 */
function BalanceBar({
  accountId,
  isLive,
}: {
  accountId: number | null
  isLive: boolean
}) {
  const { data: balance } = useAccountBalance(accountId ?? undefined)
  const { data: positions } = usePositions(accountId)
  const usdt = balance?.currencies?.USDT
  const free = toDecimal(usdt?.free ?? 0)
  const used = toDecimal(usdt?.used ?? 0)
  const total = toDecimal(usdt?.total ?? 0)
  // TD-040:单账户 uPnl = sumUnrealizedPnl(positions);任一仓位行情不可用(null)→ null 显 —
  const uPnl = sumUnrealizedPnl(positions)
  const uPnlNull = uPnl == null
  const uPnlNum = uPnlNull ? 0 : uPnl.toNumber()

  return (
    <Card className="p-5">
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <BalanceCell label="可用" value={formatMoney(free, { dp: 2 })} />
        <BalanceCell label="冻结" value={formatMoney(used, { dp: 2 })} tone="warn" />
        <BalanceCell label="总权益" value={formatMoney(total, { dp: 2 })} />
        <BalanceCell
          label="未实现盈亏"
          value={uPnlNull ? '—' : `${pnlArrow(uPnlNum)} ${formatMoney(uPnl!.abs(), { dp: 2 })}`}
          tone={uPnlNull ? undefined : uPnlNum >= 0 ? 'up' : 'down'}
        />
      </div>
      <div className="mt-2.5 border-t border-border-soft pt-2.5 text-caption text-text-muted">
        {isLive ? (
          <span className="text-accent">
            LIVE · 余额由交易所实时维护,每次查询实时拉取
          </span>
        ) : (
          <>
            PAPER 模拟盘 · 基准交易所 <strong>BINANCE</strong> · 余额本地真实化(下单冻结/成交扣减/撤单解冻)
          </>
        )}
      </div>
    </Card>
  )
}

function BalanceCell({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone?: 'warn' | 'up' | 'down'
}) {
  const toneClass =
    tone === 'warn'
      ? 'text-warning'
      : tone === 'up'
        ? 'text-up'
        : tone === 'down'
          ? 'text-down'
          : 'text-text-primary'
  return (
    <div>
      <div className="text-caption uppercase tracking-[0.05em] text-text-muted">{label}</div>
      <div className={`kq-mono-row mt-1 text-[20px] font-bold ${toneClass}`}>{value}</div>
    </div>
  )
}

/** TradingOrderBook — 共享 OrderBook 的 mock wrapper(TD-009/012 留账)。
 *  TradingPage 是 PAPER 模式,exchange=PAPER,后端 PAPER orderbook 行为取决于 TD-012
 *  「PAPER 同源行情」未做,不盲接避免空盘口。此处用确定性派生 mock(同现状,非 Math.random,
 *  避免渲染抖动)。asks 降序(高在顶,卖一在底近中间)/ bids 降序(买一在顶近中间)。
 *  gen 20 档对齐后端 orderbook depth 默认(20),让抽屉完整档 + 验证滚动。
 *  接真需 TD-012 定 PAPER orderbook 来源(镜像基准交易所 / 撮合派生 / mock)。 */
function TradingOrderBook() {
  const { asks, bids } = useMemo(() => {
    const gen = (start: number) => {
      const out: { price: number; qty: number }[] = []
      let p = start
      for (let i = 0; i < 20; i++) {
        p -= 2 + ((i * 37) % 40) / 10
        out.push({ price: p, qty: ((i * 53) % 40) / 100 + 0.01 })
      }
      return out
    }
    return { asks: gen(61250), bids: gen(61220) }
  }, [])
  return (
    <OrderBook symbol="BTC/USDT" asks={asks} bids={bids} last={61220.5} pct={2.34} badge="PERP" />
  )
}

/** OrderForm — 7 类型 + 4 TIF + 买卖 toggle + LIVE 二次确认 Dialog+Checkbox。 */
function OrderForm({
  isLive,
  accountId,
  modeAccounts,
  onAccountChange,
  onSubmitRiskReject,
}: {
  isLive: boolean
  accountId: number | null
  modeAccounts: ExchangeAccountView[]
  onAccountChange: (id: number) => void
  onSubmitRiskReject: (reason: string) => void
}) {
  const [type, setType] = useState<(typeof ORDER_TYPES)[number]>('LIMIT')
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY')
  const [price, setPrice] = useState('61200')
  const [qty, setQty] = useState('0.1')
  const [tif, setTif] = useState<(typeof TIF)[number]>('GTC')
  const [trail, setTrail] = useState('1.5')
  const [stopPrice, setStopPrice] = useState('60500')
  const [showConfirm, setShowConfirm] = useState(false)
  const [ackChecked, setAckChecked] = useState(false)
  const submitMut = useSubmitOrder()

  const priceDec = toDecimal(price || '0')
  const qtyDec = toDecimal(qty || '0')
  const notional = qtyDec.times(priceDec)
  const fee = notional.times(0.0004)

  const buildReq = (): OrderSubmitRequest => ({
    accountId: accountId ?? 0,
    symbol: SYMBOL,
    side,
    orderType: type,
    amount: qtyDec.toNumber(),
    price: MARKET_LIKE.includes(type) ? 0 : priceDec.toNumber(),
    stopPrice: (type.includes('STOP') || type.includes('TAKE_PROFIT')) && type !== 'TRAILING_STOP' ? toDecimal(stopPrice).toNumber() : 0,
    timeInForce: tif,
    expireAt: tif === 'GTD' ? '2026-12-31T23:59:59Z' : '',
    clientOrderId: '',
    marketType: MARKET_TYPE,
  })

  const submit = () => {
    if (isLive) {
      setShowConfirm(true)
      return
    }
    doSubmit()
  }
  const doSubmit = () => {
    setShowConfirm(false)
    setAckChecked(false)
    submitMut.mutate(buildReq(), {
      onSuccess: (data) => {
        toast.success(
          isLive ? '实盘订单已提交' : '订单已提交',
          { description: `${sideLabel(side)} ${qty} ${SYMBOL} · orderId ${data.orderId ?? '-'}` },
        )
      },
      onError: (e: unknown) => {
        if (e instanceof ApiError && e.code === 4105) {
          onSubmitRiskReject(e.message)
        } else if (e instanceof ApiError && (e.code === 4101 || e.code === 4102 || e.code === 4107)) {
          toast.error(e.message || '下单失败')
        } else if (e instanceof ApiError && e.isUnauthorized) {
          toast.error('未认证,请重新登录')
        } else {
          toast.error('下单失败,请重试')
        }
      },
    })
  }

  return (
    <Card className="p-5" style={{ borderTop: `3px solid ${isLive ? 'var(--accent)' : 'var(--up)'}` }}>
      <div className="mb-3.5 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <strong className="text-body font-bold text-text-primary">下单</strong>
          {isLive ? (
            <span className="kq-live-badge">● LIVE · 真金白银</span>
          ) : (
            <span className="kq-paper-badge">PAPER · 模拟</span>
          )}
        </div>
        <Select
          value={accountId != null ? String(accountId) : undefined}
          onValueChange={(v) => onAccountChange(parseInt(v, 10))}
        >
          <SelectTrigger className="w-auto text-caption" size="sm">
            <SelectValue placeholder="选择账户" />
          </SelectTrigger>
          <SelectContent>
            {modeAccounts.map((a) => (
              <SelectItem key={a.id} value={String(a.id)}>
                {a.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* BUY/SELL toggle */}
      <div className="mb-3.5 grid grid-cols-2 gap-1.5">
        <button
          type="button"
          onClick={() => setSide('BUY')}
          className="kq-press rounded-md border px-2.5 py-2.5 text-body-sm font-bold transition-all duration-150"
          style={{
            borderColor: side === 'BUY' ? 'var(--up)' : 'var(--border-soft)',
            background: side === 'BUY' ? 'color-mix(in oklab, var(--up) 15%, transparent)' : 'var(--surface-card-2)',
            color: side === 'BUY' ? 'var(--up)' : 'var(--text-secondary)',
            cursor: 'pointer',
          }}
        >
          买入 BUY
        </button>
        <button
          type="button"
          onClick={() => setSide('SELL')}
          className="kq-press rounded-md border px-2.5 py-2.5 text-body-sm font-bold transition-all duration-150"
          style={{
            borderColor: side === 'SELL' ? 'var(--down)' : 'var(--border-soft)',
            background: side === 'SELL' ? 'color-mix(in oklab, var(--down) 15%, transparent)' : 'var(--surface-card-2)',
            color: side === 'SELL' ? 'var(--down)' : 'var(--text-secondary)',
            cursor: 'pointer',
          }}
        >
          卖出 SELL
        </button>
      </div>

      {/* 7 order types */}
      <div className="mb-2.5 grid grid-cols-2 gap-2">
        {ORDER_TYPES.map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setType(t)}
            className="kq-press rounded-md border px-2 py-1.5 text-caption font-semibold transition-all"
            style={{
              borderColor: type === t ? 'var(--accent)' : 'var(--border-soft)',
              background: type === t ? 'var(--accent-soft)' : 'var(--surface-card-2)',
              color: type === t ? 'var(--accent)' : 'var(--text-secondary)',
              cursor: 'pointer',
              letterSpacing: '0.02em',
            }}
          >
            {t}
          </button>
        ))}
      </div>

      {/* price / qty / stopPrice / trail */}
      <div className="mb-2.5 grid grid-cols-2 gap-2.5">
        <div>
          <Label className="text-caption text-text-muted">价格 (USDT)</Label>
          <Input
            className="kq-mono-row mt-1"
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            disabled={MARKET_LIKE.includes(type)}
            style={{ opacity: MARKET_LIKE.includes(type) ? 0.5 : 1 }}
          />
        </div>
        <div>
          <Label className="text-caption text-text-muted">数量 (BTC)</Label>
          <Input className="kq-mono-row mt-1" value={qty} onChange={(e) => setQty(e.target.value)} />
        </div>
        {type === 'TRAILING_STOP' && (
          <div className="col-span-2">
            <Label className="text-caption text-text-muted">追踪幅度 (%)</Label>
            <Input className="kq-mono-row mt-1" value={trail} onChange={(e) => setTrail(e.target.value)} />
          </div>
        )}
        {(type.includes('STOP') || type.includes('TAKE_PROFIT')) && type !== 'TRAILING_STOP' && (
          <div className="col-span-2">
            <Label className="text-caption text-text-muted">触发价</Label>
            <Input className="kq-mono-row mt-1" value={stopPrice} onChange={(e) => setStopPrice(e.target.value)} />
          </div>
        )}
      </div>

      {/* 4 TIF */}
      <div className="mb-3.5 flex gap-1.5">
        {TIF.map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setTif(t)}
            className="kq-press flex-1 rounded-md border px-1.5 py-1.5 text-caption font-semibold transition-all"
            style={{
              borderColor: tif === t ? 'var(--accent)' : 'var(--border-soft)',
              background: tif === t ? 'var(--accent-soft)' : 'var(--surface-card-2)',
              color: tif === t ? 'var(--accent)' : 'var(--text-secondary)',
              cursor: 'pointer',
            }}
          >
            {t}
          </button>
        ))}
      </div>

      {/* notional / fee / risk */}
      <div className="mb-3.5 rounded-md bg-surface-card-2 p-2.5">
        <div className="flex justify-between text-caption text-text-muted">
          <span>订单金额</span>
          <span className="kq-mono-row font-bold text-text-primary">{formatMoney(notional, { dp: 2 })} USDT</span>
        </div>
        <div className="mt-1 flex justify-between text-caption text-text-muted">
          <span>预估手续费</span>
          <span className="kq-mono-row">{formatMoney(fee, { dp: 4 })}</span>
        </div>
        {isLive && (
          <div className="mt-1 flex justify-between text-caption text-down">
            <span>风控闸门</span>
            <span className="font-semibold">MAX_NOTIONAL · 检查中</span>
          </div>
        )}
      </div>

      <button
        type="button"
        onClick={submit}
        disabled={submitMut.isPending}
        className="kq-press w-full rounded-md p-3 text-body font-bold text-on-accent transition-all disabled:opacity-50"
        style={{ background: side === 'BUY' ? 'var(--up)' : 'var(--down)', cursor: 'pointer' }}
      >
        {sideLabel(side)} {qty} {SYMBOL}
        {isLive && ' · 真金白银'}
      </button>

      {isLive && (
        <div className="mt-2.5 rounded-md border border-accent bg-accent-soft p-2.5 text-caption leading-relaxed text-accent">
          ⚠ LIVE 账户订单为真金白银,提交前会通过风控闸门(MAX_NOTIONAL / DAILY_LOSS_LIMIT / ORDER_FREQUENCY),高风险操作需二次确认。
        </div>
      )}
      {!isLive && (
        <div className="mt-2.5 rounded-md border border-border-soft border-dashed bg-surface-card-2 p-2.5 text-caption leading-relaxed text-text-muted">
          PAPER 模拟盘使用 10 万 USDT 虚拟资金 + 基准交易所行情撮合,可重置。
        </div>
      )}

      {/* LIVE 下单确认 Dialog + Checkbox */}
      <Dialog open={showConfirm} onOpenChange={(o) => { setShowConfirm(o); if (!o) setAckChecked(false) }}>
        <DialogContent className="max-w-[460px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="size-4 text-down" aria-hidden />
              实盘下单确认
            </DialogTitle>
            <DialogDescription>真实交易所、真实资金、真实手续费。请仔细确认订单参数。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div className="rounded-md border border-accent bg-accent-soft p-3.5">
              <div className="text-body-sm font-bold text-accent">这是 LIVE 实盘订单</div>
              <div className="mt-1 text-caption leading-relaxed text-accent">
                真实交易所、真实资金、真实手续费。请仔细确认订单参数。
              </div>
            </div>
            <div className="rounded-md border border-border-soft bg-surface-card-2 p-3.5">
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">订单类型</span>
                <strong>{orderTypeLabel(type)}</strong>
              </div>
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">方向</span>
                <span className={side === 'BUY' ? 'text-up' : 'text-down'}>{sideLabel(side)}</span>
              </div>
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">价格</span>
                <span className="kq-mono-row">{MARKET_LIKE.includes(type) ? '市价' : price}</span>
              </div>
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">数量</span>
                <span className="kq-mono-row">{qty} BTC</span>
              </div>
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">总金额</span>
                <span className="kq-mono-row font-bold">{formatMoney(notional, { dp: 2 })} USDT</span>
              </div>
            </div>
            <label className="flex items-start gap-2 text-body-sm text-text-secondary">
              <Checkbox checked={ackChecked} onCheckedChange={(v) => setAckChecked(v === true)} />
              <span>我已确认这是实盘订单,知悉风险</span>
            </label>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowConfirm(false)}>
              取消
            </Button>
            <button
              type="button"
              onClick={doSubmit}
              disabled={!ackChecked || submitMut.isPending}
              className="kq-press rounded-md p-2.5 text-body-sm font-bold text-on-accent transition-all disabled:opacity-50"
              style={{ background: 'var(--down)', border: 'none', cursor: 'pointer' }}
            >
              确认下单(真金白银)
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

/** PositionsTable — 单账户持仓(TD-040:uPnl 用 PositionDto.unrealizedPnl,行情不可用 null 显 —)。 */
function PositionsTable({
  isLive,
  accountId,
  onClose,
}: {
  isLive: boolean
  accountId: number | null
  onClose: (p: PositionDto) => void
}) {
  const { data, isLoading } = usePositions(accountId)
  const list = data ?? []
  return (
    <Card className="p-5">
      <SectionTitle
        title="持仓"
        sub={isLive ? '实盘账户持仓' : 'PAPER 模拟盘持仓'}
        right={<Chip label={`${list.length} 个`} />}
      />
      <div className="overflow-auto">
        <Table>
          <TableHeader>
            <TableRow className="text-left text-caption uppercase tracking-[0.04em] text-text-muted">
              <TableHead className="px-3 py-2">账户</TableHead>
              <TableHead className="px-3 py-2">Symbol</TableHead>
              <TableHead className="px-3 py-2">方向</TableHead>
              <TableHead className="px-3 py-2 text-right">数量</TableHead>
              <TableHead className="px-3 py-2 text-right">均价</TableHead>
              <TableHead className="px-3 py-2 text-right">未实现</TableHead>
              <TableHead className="px-3 py-2 text-right">已实现</TableHead>
              <TableHead className="px-3 py-2 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="kq-mono-row">
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={8} className="p-6">
                  <LoadingState />
                </TableCell>
              </TableRow>
            ) : list.length === 0 ? (
              <TableRow>
                <TableCell colSpan={8} className="p-6">
                  <EmptyState title="无持仓" description="当前账户无持仓" />
                </TableCell>
              </TableRow>
            ) : (
              list.map((p) => {
                const isLong = p.side === 'LONG'
                const rPnl = toDecimal(p.realizedPnl)
                // TD-040:unrealizedPnl 契约标 number 但运行时可 null(行情不可用),cast 守
                const uPnl = p.unrealizedPnl as number | null
                const uPnlNull = uPnl == null
                return (
                  <TableRow key={p.positionId}>
                    <TableCell className="px-3 py-2.5">
                      {isLive ? <span className="kq-live-badge">LIVE</span> : <span className="kq-paper-badge">PAPER</span>}
                    </TableCell>
                    <TableCell className="px-3 py-2.5">{p.symbol}</TableCell>
                    <TableCell className="px-3 py-2.5">
                      <span className={`font-bold ${isLong ? 'text-up' : 'text-down'}`}>
                        {p.side}
                      </span>
                    </TableCell>
                    <TableCell className="px-3 py-2.5 text-right">{formatMoney(toDecimal(p.qty), { dp: 4 })}</TableCell>
                    <TableCell className="px-3 py-2.5 text-right">{formatMoney(toDecimal(p.avgEntryPrice), { dp: 2 })}</TableCell>
                    <TableCell className={`px-3 py-2.5 text-right ${uPnlNull ? 'text-text-muted' : pnlTextClass(toDecimal(uPnl).toNumber())}`}>
                      {uPnlNull ? '—' : <>{pnlArrow(toDecimal(uPnl).toNumber())}{formatMoney(toDecimal(uPnl).abs(), { dp: 2 })}</>}
                    </TableCell>
                    <TableCell className={`px-3 py-2.5 text-right ${pnlTextClass(rPnl.toNumber())}`}>
                      {pnlArrow(rPnl.toNumber())}{formatMoney(rPnl.abs(), { dp: 2 })}
                    </TableCell>
                    <TableCell className="px-3 py-2.5 text-right">
                      <Button variant="ghost" size="sm" onClick={() => onClose(p)}>
                        平仓
                      </Button>
                    </TableCell>
                  </TableRow>
                )
              })
            )}
          </TableBody>
        </Table>
      </div>
    </Card>
  )
}

/** OrdersTable — 当前订单(useOrders + normalizeOrderStatus,TD-039)。 */
function OrdersTable({ accountId, isLive }: { accountId: number | null; isLive: boolean }) {
  const { data, isLoading } = useOrders(accountId, { pageSize: 50 })
  const page = data?.content ?? []
  return (
    <Card className="p-5">
      <SectionTitle
        title="当前订单"
        sub={isLive ? '实盘挂单 / 部分成交' : 'PAPER 挂单 / 部分成交'}
        right={
          <div className="flex gap-1.5">
            <button type="button" className="rounded-md border border-accent bg-accent-soft px-2 py-1 text-caption text-accent">活动</button>
            <button type="button" className="rounded-md border border-border-soft bg-surface-card-2 px-2 py-1 text-caption text-text-secondary">全部</button>
            <button type="button" className="rounded-md border border-border-soft bg-surface-card-2 px-2 py-1 text-caption text-text-secondary">已撤销</button>
          </div>
        }
      />
      <div className="overflow-auto">
        <Table>
          <TableHeader>
            <TableRow className="text-left text-caption uppercase tracking-[0.04em] text-text-muted">
              <TableHead className="px-3 py-2">订单ID</TableHead>
              <TableHead className="px-3 py-2">Symbol</TableHead>
              <TableHead className="px-3 py-2">类型</TableHead>
              <TableHead className="px-3 py-2">方向</TableHead>
              <TableHead className="px-3 py-2 text-right">价格</TableHead>
              <TableHead className="px-3 py-2 text-right">数量</TableHead>
              <TableHead className="px-3 py-2">状态</TableHead>
              <TableHead className="px-3 py-2 text-right">时间</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="kq-mono-row">
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={8} className="p-6">
                  <LoadingState />
                </TableCell>
              </TableRow>
            ) : page.length === 0 ? (
              <TableRow>
                <TableCell colSpan={8} className="p-6">
                  <EmptyState title="无订单" description="当前账户无订单" />
                </TableCell>
              </TableRow>
            ) : (
              page.map((o: OrderDetailDto) => {
                const isBuy = o.side.toUpperCase() === 'BUY'
                return (
                  <TableRow key={o.orderId}>
                    <TableCell className="px-3 py-2.5">{o.orderId}</TableCell>
                    <TableCell className="px-3 py-2.5">{o.symbol}</TableCell>
                    <TableCell className="px-3 py-2.5">
                      <Chip label={orderTypeLabel(o.orderType)} />
                    </TableCell>
                    <TableCell className={`px-3 py-2.5 font-bold ${isBuy ? 'text-up' : 'text-down'}`}>
                      {sideLabel(o.side)}
                    </TableCell>
                    <TableCell className="px-3 py-2.5 text-right">
                      {o.price ? formatMoney(toDecimal(o.price), { dp: 2 }) : '—'}
                    </TableCell>
                    <TableCell className="px-3 py-2.5 text-right">{formatMoney(toDecimal(o.amount), { dp: 4 })}</TableCell>
                    <TableCell className="px-3 py-2.5">
                      <OrderStatusBadge status={normalizeOrderStatus(o.status)} />
                    </TableCell>
                    <TableCell className="px-3 py-2.5 text-right text-text-muted">
                      {o.createdAt ? o.createdAt.slice(5, 16).replace('T', ' ') : '—'}
                    </TableCell>
                  </TableRow>
                )
              })
            )}
          </TableBody>
        </Table>
      </div>
    </Card>
  )
}
