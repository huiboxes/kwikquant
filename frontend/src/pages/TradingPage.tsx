import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { AlertTriangle, Code2 } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectSeparator,
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
import { KlineChart } from '@/components/charts/KlineChart'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { useUiStore } from '@/stores/uiStore'
import { useMarketStore } from '@/stores/marketStore'
import { useAuthStore } from '@/stores/authStore'
import { useAccounts, useAccountBalance } from '@/hooks/useAccounts'
import { useOrderBook } from '@/hooks/useMarket'
import { useSymbolSnapshot } from '@/hooks/useSymbolSnapshot'
import { useKlineChart } from '@/hooks/useKlineChart'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Slider } from '@/components/ui/slider'
import { useOrders, usePositions, useSubmitOrder, useClosePosition, useCancelOrder, useOrderFills } from '@/hooks/useTrading'
import {
  normalizeOrderStatus,
  sideLabel,
  orderTypeLabelCn,
  type OrderSubmitRequest,
} from '@/api/order'
import { cn } from '@/lib/utils'
import type { components } from '@/types/api-gen'
import { toDecimal, formatMoney } from '@/lib/money'
import { formatDateTime } from '@/lib/format'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import { sumUnrealizedPnl } from '@/lib/positionPnl'
import { ApiError } from '@/lib/http'
import { useQueryClient } from '@tanstack/react-query'
import { useLiquidationTopic } from '@/lib/ws/useLiquidationTopic'
import { positionKeys } from '@/api/_queryKeys'

/**
 * TradingPage — 交易页(照原型 done-design/components/TradingPage.jsx port)。
 *
 * 与原型差异(适配后端契约,逐项说明):
 *  - OrderDetailDto.status 后端 9 态枚举(NEW|PENDING_NEW|SUBMITTED|PARTIALLY_FILLED|FILLED|PENDING_CANCEL|CANCELLED|REJECTED|EXPIRED)
 *    → OrderStatusBadge ws 命名(normalizeOrderStatus 映射 PENDING_NEW→PENDING/CANCELLED→CANCELED 等)。
 *  - PositionDto.unrealizedPnl/currentPrice(行情不可用 null)→ uPnl 列用真实字段,null 显 —;BalanceBar 单账户 uPnl = sumUnrealizedPnl(positions)。
 *  - 风控拒 POST /orders 200+code=4105(非 HTTP 错误)→ useSubmitOrder onError
 *    检查 ApiError.code===4105 → toast.error(reason) + navigate('/risk')。
 *  - marketType 由 URL ?marketType= 驱动(SPOT/PERP segment 切换);symbol 由 URL ?symbol= 驱动(⌘K 选标的 → /trade?symbol=X)。
 *  - POST /positions/{id}/close 反向市价单平仓 → useClosePosition + ConfirmDialog(LIVE destructive)。
 *  -  已接:POST /accounts/{id}/paper/reset → 重置归 Settings 交易账户 tab(),TradingPage 不再含重置入口。
 *  - WS 推送(useTradingEvents 全局订阅 /topic/orders + /topic/fills +
 *    /topic/positions + /topic/portfolio,收到 invalidate 对应 queryKeys,各页自动刷新)。
 *  - K线 useKlineChart(REST 500 根 + before 分页 + WS 增量);OrderBook useOrderBook(真端点 GET /market/orderbook)。
 *
 * IA 重排:删 mode switcher banner(SegMode)+ sticky LIVE badge + 切 LIVE Dialog + 重置 AlertDialog。
 *  - mode 切换归 TopBar TradeModeToggle(全局 chrome 级,首次切 LIVE 走会话级确认)。
 *  - 重置归 Settings 交易账户 tab(账户级破坏性操作,跳页 + ConfirmDialog)。
 *  - 首元素 BalanceBar(4 格余额,无技术文案行)。
 *  - 空账户引导:modeAccounts 空 → OrderForm EmptyState「去添加」跳 /settings?tab=accounts。
 *
 * PAPER/LIVE 强区分(多层防护,用户绝不误把实盘当模拟):
 *  - OrderForm 顶部徽章(● 实盘 / 模拟)+ 交易所风格下单面板(无 borderTop 色条)
 *  - LIVE 下单 Dialog + Checkbox(必须勾选"知悉风险")
 *  - 平仓 destructive ConfirmDialog(LIVE destructive)
 *
 * 文案原则(memory feedback_copy_user_language_no_impl_leak):用户可见处中文 模拟盘/实盘,
 * 不泄露 PAPER/LIVE 枚举/余额来源/冻结机制/基准交易所/撮合方式/风控规则名(MAX_NOTIONAL 等);
 * 真金白银 只在下单按钮 + 实盘确认弹窗(决策点)。
 *
 * 金额:free/used/total/qty/avg/realizedPnl/notional/fee 全 toDecimal + formatMoney,
 * notional = qty × price(decimal.js .times),fee = notional × 0.0004。展示全 kq-mono-row。
 * 涨跌(买卖/LONG/SHORT/realizedPnl)用 pnlArrow + pnlTextClass + 文本标签(a11y)。
 * 图标 lucide-react,不用 emoji。
 */
type PositionDto = components['schemas']['PositionDto']
type OrderDetailDto = components['schemas']['OrderDetailDto']
type ExchangeAccountView = components['schemas']['ExchangeAccountView']

/** persistent symbol(同后端 application.yaml OKX persistent-symbols),判断 sel 是否 persistent。
 * 减到 3 个主流(BTC/ETH/SOL)预热实时 WS;其余 symbol on-demand WS SUBSCRIBE 起 worker。 */
const PERSISTENT_SYMBOLS = ['BTC/USDT', 'ETH/USDT', 'SOL/USDT'] as const
const ORDER_TYPES = [
  'LIMIT',
  'MARKET',
  'STOP_MARKET',
  'STOP_LIMIT',
  'TAKE_PROFIT_MARKET',
  'TAKE_PROFIT_LIMIT',
  'TRAILING_STOP',
] as const
const MARKET_LIKE: readonly string[] = ['MARKET', 'STOP_MARKET', 'TAKE_PROFIT_MARKET', 'TRAILING_STOP']
const TIF = ['GTC', 'IOC', 'FOK', 'GTD'] as const
/**
 * PERP 合约下单:4 按钮(开多/开空/平多/平空),positionEffect 枚举对齐后端 OrderSubmitRequest。
 * 用户可见文案用中文(开多/开空/平多/平空),不暴露 OPEN_LONG 等枚举字面量;tag 是 a11y title。
 * tone up=多(绿)/down=空(红);strong=true 开仓态强对比(实色填充),false 平仓态弱化(soft bg)。
 */
type PerpAction = 'OPEN_LONG' | 'OPEN_SHORT' | 'CLOSE_LONG' | 'CLOSE_SHORT'
const PERP_ACTIONS: { key: PerpAction; label: string; tone: 'up' | 'down'; strong: boolean; tag: string }[] = [
  { key: 'OPEN_LONG', label: '开多', tone: 'up', strong: true, tag: '开多仓 · 做多' },
  { key: 'OPEN_SHORT', label: '开空', tone: 'down', strong: true, tag: '开空仓 · 做空' },
  { key: 'CLOSE_LONG', label: '平多', tone: 'up', strong: false, tag: '平掉多仓' },
  { key: 'CLOSE_SHORT', label: '平空', tone: 'down', strong: false, tag: '平掉空仓' },
]
/** 杠杆预设档位 1-125x,9 档(对齐 3.3 原型 + DESIGN.md components.leverage-preset)。 */
const LEVERAGE_PRESETS = [1, 2, 5, 10, 25, 50, 75, 100, 125] as const
const LEVERAGE_MIN = 1
const LEVERAGE_MAX = 125
/** 维持保证金率简化常量(0.5%,实际随档位变化;后端风控接真后改返回)。 */
const MAINT_MARGIN_RATE = 0.005
const INTERVAL_TABS = [
  { label: '1m', value: '_1m' },
  { label: '5m', value: '_5m' },
  { label: '15m', value: '_15m' },
  { label: '1h', value: '_1h' },
  { label: '4h', value: '_4h' },
  { label: '1d', value: '_1d' },
] as const

export function TradingPage() {
  const navigate = useNavigate()
  const tradeMode = useUiStore((s) => s.tradeMode)

  const isLive = tradeMode === 'LIVE'
  const [closeTarget, setCloseTarget] = useState<PositionDto | null>(null)
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null)

  // 3.4/3.5:挂强平 WS 订阅(/topic/liquidations/{userId})。
  // 收到事件:toast 中文文案(不暴露 LiquidationEvent 枚举/reason)+ invalidate 持仓 query
  // (3.5 补:强平后持仓应消失/qty=0,react-query invalidate positions 让 PositionsTable 自动 refetch)。
  // 持仓 query key 见 positionKeys(全 invalidate,不止当前账户 —— 跨账户强平也能更新)。
  const queryClient = useQueryClient()
  const userId = useAuthStore((s) => s.user?.userId ?? null)
  useLiquidationTopic(userId, (liq) => {
    const sideLabelCn = liq.positionSide === 'LONG' ? '多' : liq.positionSide === 'SHORT' ? '空' : ''
    toast.error('持仓已被强平', {
      description: `持仓 #${liq.positionId} ${sideLabelCn}仓被强平,已实现盈亏 ${formatMoney(toDecimal(liq.realizedPnl ?? 0), { dp: 2 })} USDT`,
    })
    // 强平 → 持仓变动(qty=0 或消失)+ 余额变动(释放保证金 / 已实现盈亏入账)。
    // positions/balance/portfolio 都 invalidate,让各表实时刷新(WS 广播兜底,这里显式触发)。
    queryClient.invalidateQueries({ queryKey: positionKeys.all })
    queryClient.invalidateQueries({ queryKey: ['account', 'balance'] })
    queryClient.invalidateQueries({ queryKey: ['portfolio'] })
  })

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

  // sel 从 URL ?symbol= 驱动(⌘K 选标的 → /trade?symbol=X);默认 BTC/USDT。
  // marketType 从 URL ?marketType= 驱动(顶部现货/合约 tab 切换写 URL;默认 SPOT)。贯穿
  // snapshot/kline/orderbook/orderform,MarketPage 合约 tab 行点击带 ?marketType=PERP 跳来即生效。
  const [params, setSearchParams] = useSearchParams()
  const sel = params.get('symbol') ?? 'BTC/USDT'
  const marketType: 'SPOT' | 'PERP' = params.get('marketType') === 'PERP' ? 'PERP' : 'SPOT'
  const setMarketType = (mt: 'SPOT' | 'PERP') => {
    const next = new URLSearchParams(params)
    next.set('marketType', mt)
    setSearchParams(next, { replace: true })
  }
  // 当前账户 exchange(PAPER 取基准 OKX,LIVE 取实盘账户 exchange);兜底 OKX。
  const selAccount = modeAccounts.find((a) => a.id === effectiveAccountId)
  const exchange = selAccount?.exchange ?? 'OKX'
  // K 线 + ticker 真数据(sel 驱动;非 persistent 走后端 CCXT fallback,stale=true 标非实时快照)。
  // K 线 interval 6 档(1m/5m/15m/1h/4h/1d),useKlineChart 封装 500 根首屏 + before 分页 + WS 增量。
  const [interval, setIntervalTab] = useState<string>('_15m')
  // 标的实时快照(块 A:REST 首拉 + WS tick 聚合,见 useSymbolSnapshot)。OHLC/lastPrice 读 snap,
  // WS 推全量 Ticker record 覆盖 REST → 实时跳。连接状态归 TopBar WsConnectionIndicator。
  const { data: snap } = useSymbolSnapshot(exchange, marketType, sel, PERSISTENT_SYMBOLS)
  const selPct = toDecimal(snap?.percentage ?? 0).toNumber()
  const {
    candles,
    updateCandle,
    loadingMore,
    onLoadMore,
    isLoading: klinesLoading,
    error: klinesError,
    refetch: refetchKlines,
  } = useKlineChart({ exchange, marketType, symbol: sel, interval })
  const setCmdOpen = useUiStore((s) => s.setCmdOpen)
  // 平仓 mutation(后端端点已就绪,接 ConfirmDialog)
  const closeMut = useClosePosition()

  // persistent 8 symbol 全局订阅 WS(同 MarketPage;切页 unmount 退订,但 persistent worker 后端常驻,
  // WS 重连由 ConnectionManager onConnect 重订阅)。snap 含 WS tick → OHLC/lastPrice 实时跳。
  // persistent 固定预热 SPOT 8 symbol(PERSISTENT_SYMBOLS 是 SPOT canonical);切 PERP 时 sel 走
  // useSymbolSnapshot on-demand worker(WS SUBSCRIBE 起 PERP worker),不重订 persistent。
  useEffect(() => {
    const unsub = useMarketStore.getState().subscribeTickers(
      exchange,
      'SPOT',
      PERSISTENT_SYMBOLS,
    )
    return unsub
  }, [exchange])

  // 非 persistent sel 的 WS 订阅生命周期归 useSymbolSnapshot 内部(WS SUBSCRIBE 起后端 worker +
  // subscribeTicker 订 destination;切走/卸载 unsub → WS UNSUBSCRIBE 退)。persistent 已被上面订阅,
  // 引用计数 refCount++(共享单订阅,最后一个 unmount 才退)。


  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
  }
  if (isLoading) return <LoadingState />

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Balance */}
      <BalanceBar accountId={effectiveAccountId} />

      {/* Main 3-col */}
      <div>
        <div className="grid grid-cols-2 gap-1.5 md:grid-cols-[1.4fr_320px_1fr] md:gap-3">
          {/* Chart */}
          <Card className="col-span-2 flex flex-col overflow-hidden p-0 md:col-span-1">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border-soft px-3.5 py-2.5">
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setCmdOpen(true)}
                  className="text-body-sm font-bold text-text-primary transition-colors hover:text-accent"
                  title="⌘K 切换标的"
                >
                  {sel} · K 线
                </button>
              </div>
              <div className="flex items-center gap-2">
                <Tabs value={interval} onValueChange={setIntervalTab}>
                  <TabsList>
                    {INTERVAL_TABS.map((t) => (
                      <TabsTrigger key={t.value} value={t.value}>
                        {t.label}
                      </TabsTrigger>
                    ))}
                  </TabsList>
                </Tabs>
                <Button variant="ghost" size="sm" asChild>
                  <Link
                    to={`/strategy?symbol=${encodeURIComponent(sel)}&marketType=${marketType}`}
                    title={`用 ${sel} 写策略`}
                  >
                    <Code2 className="size-4" aria-hidden /> 写策略
                  </Link>
                </Button>
              </div>
            </div>
            <div className="flex-1 overflow-hidden p-2.5">
              {klinesLoading ? (
                <LoadingState rows={4} />
              ) : klinesError ? (
                <ErrorState message={klinesError.message} onRetry={refetchKlines} />
              ) : (
                <KlineChart
                  data={candles}
                  updateCandle={updateCandle}
                  onLoadMore={onLoadMore}
                  loadingMore={loadingMore}
                  height={440}
                />
              )}
            </div>
            <div className="flex gap-3.5 border-t border-border-soft px-3.5 py-2 text-caption text-text-muted">
              <span>
                O <Ticker base={snap?.open ?? 0} chg={selPct} dp={2} />
              </span>
              <span className="kq-mono-row text-up">H {formatMoney(toDecimal(snap?.high ?? 0), { dp: 2 })}</span>
              <span className="kq-mono-row text-down">L {formatMoney(toDecimal(snap?.low ?? 0), { dp: 2 })}</span>
              <span>
                C <Ticker base={snap?.last ?? 0} chg={selPct} dp={2} />
              </span>
              <span>
                Vol <span className="kq-mono-row">{formatMoney(toDecimal(snap?.quoteVolume ?? 0), { dp: 0 })}</span>
              </span>
            </div>
          </Card>
          {/* Order book — 共享 OrderBook 组件,真数据(useOrderBook REST 轮询 + useSymbolSnapshot 取 last/pct)。 */}
          <TradingOrderBook exchange={exchange} marketType={marketType} symbol={sel} />
          {/* Order form */}
          <OrderForm
            isLive={isLive}
            accountId={effectiveAccountId}
            modeAccounts={modeAccounts}
            onAccountChange={setSelectedAccountId}
            symbol={sel}
            marketType={marketType}
            onMarketTypeChange={setMarketType}
            lastPrice={snap?.last}
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

      {/* 平仓 ConfirmDialog(POST /positions/{id}/close 反向市价单,LIVE destructive)。
          PERP 态显方向(多/空)+ 杠杆 + 保证金模式 + 强平价 + 数量;SPOT 只显方向+数量。
          后端 PositionController.close 按 pos.marketType 派生 positionEffect(CLOSE_LONG/CLOSE_SHORT),
          前端只传 positionId,不传 positionEffect。 */}
      <ConfirmDialog
        open={closeTarget != null}
        onOpenChange={(o) => {
          if (!o) setCloseTarget(null)
        }}
        title={isLive ? '确认实盘平仓' : '确认平仓'}
        description={`平掉 ${closeTarget?.symbol ?? ''} ${closeTarget ? (closeTarget.positionSide === 'LONG' ? '多' : closeTarget.positionSide === 'SHORT' ? '空' : closeTarget.side === 'LONG' ? '多' : closeTarget.side === 'SHORT' ? '空' : '空') : ''} 持仓 ${closeTarget ? formatMoney(toDecimal(closeTarget.qty), { dp: 4 }) : ''}。以反向市价单平掉全部数量,走完整下单链路(风控+余额冻结)。`}
        confirmLabel={closeMut.isPending ? '平仓中…' : (closeTarget ? (closeTarget.positionSide === 'LONG' ? '平多' : closeTarget.positionSide === 'SHORT' ? '平空' : '平仓') : '平仓')}
        destructive={isLive}
        loading={closeMut.isPending}
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
      >
        {/* PERP 态额外显示合约参数(杠杆/保证金模式/强平价);SPOT 态不显。 */}
        {closeTarget && (closeTarget.positionSide === 'LONG' || closeTarget.positionSide === 'SHORT') ? (
          <div className="mb-3 grid grid-cols-2 gap-2 rounded-md border border-border-soft bg-surface-card-2 p-3 text-caption">
            <div className="flex justify-between">
              <span className="text-text-muted">杠杆</span>
              <span className="kq-mono-row font-bold">{closeTarget.leverage}x</span>
            </div>
            <div className="flex justify-between">
              <span className="text-text-muted">保证金模式</span>
              <span className="font-bold">
                {closeTarget.marginMode === 'ISOLATED' ? '逐仓' : closeTarget.marginMode === 'CROSS' ? '全仓' : '—'}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-text-muted">强平价</span>
              <span className="kq-mono-row font-bold text-warning">
                {closeTarget.liquidationPrice != null && closeTarget.liquidationPrice !== 0
                  ? formatMoney(toDecimal(closeTarget.liquidationPrice), { dp: 2 })
                  : '—'}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-text-muted">方向</span>
              <span className={`font-bold ${closeTarget.positionSide === 'LONG' ? 'text-up' : 'text-down'}`}>
                {closeTarget.positionSide === 'LONG' ? '多' : '空'}
              </span>
            </div>
          </div>
        ) : null}
      </ConfirmDialog>
    </div>
  )
}

/** BalanceBar — 4 格:可用/冻结/总权益/未实现盈亏。 */
function BalanceBar({
  accountId,
}: {
  accountId: number | null
}) {
  const { data: balance } = useAccountBalance(accountId ?? undefined)
  const { data: positions } = usePositions(accountId)
  const usdt = balance?.currencies?.USDT
  const free = toDecimal(usdt?.free ?? 0)
  const used = toDecimal(usdt?.used ?? 0)
  const total = toDecimal(usdt?.total ?? 0)
  // 单账户 uPnl = sumUnrealizedPnl(positions);任一仓位行情不可用(null)→ null 显 —
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

/** TradingOrderBook — 共享 OrderBook 真数据 wrapper(useOrderBook REST 轮询 3s + useSymbolSnapshot 取 last/pct)。
 *  sel 驱动:非 persistent symbol 走后端 CCXT fetchOrderBook + REST ticker 首拉,WS tick 实时覆盖 last。
 *  react-query queryKey 与父 useSymbolSnapshot 同 → 缓存共享不重复请求;marketStore 引用计数(refCount Map)不重复订阅。 */
function TradingOrderBook({ exchange, marketType, symbol }: { exchange: string; marketType: 'SPOT' | 'PERP'; symbol: string }) {
  const { data: book, isLoading, isError } = useOrderBook(exchange, marketType, symbol)
  const { data: tick } = useSymbolSnapshot(exchange, marketType, symbol, PERSISTENT_SYMBOLS)
  const { asks, bids } = useMemo(
    () => ({
      asks: (book?.asks ?? []).map((l) => ({ price: l.price ?? 0, qty: l.qty ?? 0 })),
      bids: (book?.bids ?? []).map((l) => ({ price: l.price ?? 0, qty: l.qty ?? 0 })),
    }),
    [book],
  )
  const last = tick?.last ?? 0
  const pct = toDecimal(tick?.percentage ?? 0).toNumber()
  return (
    <OrderBook
      symbol={symbol}
      asks={asks}
      bids={bids}
      last={last}
      pct={pct}
      loading={isLoading}
      error={isError}
      badge={marketType}
    />
  )
}

/**
 * OrderForm — 交易所风格下单面板。
 *  - BUY/SELL:Tabs 切换(交互同行情页现货/合约),active 用 up/down 色。
 *  - 委托类型(TIF)下拉,挂 BUY/SELL 下。
 *  - 价格 + 下单类型下拉(中文,7 类型)同行;触发价/追踪幅度按类型条件显示。
 *  - 数量 → 5 档滑动条(0/25/50/75/100%,按可用 quote 反算数量)→ 交易额(含可用/手续费)。
 *  - LIVE 二次确认 Dialog + Checkbox。
 */
function OrderForm({
  isLive,
  accountId,
  modeAccounts,
  onAccountChange,
  onSubmitRiskReject,
  symbol,
  marketType,
  onMarketTypeChange,
  lastPrice,
}: {
  isLive: boolean
  accountId: number | null
  modeAccounts: ExchangeAccountView[]
  onAccountChange: (id: number) => void
  onSubmitRiskReject: (reason: string) => void
  symbol: string
  /** 市场类型(SPOT 现货 / PERP 合约),下单 body 透传后端 OrderSubmitRequest.marketType。 */
  marketType: 'SPOT' | 'PERP'
  /** 切换市场类型(驱动整页行情+下单卡形态;segment 在本卡顶部,贴近它实际改变的下单区)。 */
  onMarketTypeChange: (mt: 'SPOT' | 'PERP') => void
  /** 最新成交价,市价类订单按可用金额反算数量时用。 */
  lastPrice: number | undefined
}) {
  const navigate = useNavigate()
  const [type, setType] = useState<(typeof ORDER_TYPES)[number]>('LIMIT')
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY')
  const [price, setPrice] = useState('')
  const [qty, setQty] = useState('0.1')
  const [tif, setTif] = useState<(typeof TIF)[number]>('GTC')
  const [trail, setTrail] = useState('1.5')
  const [stopPrice, setStopPrice] = useState('')
  const [pct, setPct] = useState(0) // 滑动条档位 0/25/50/75/100
  const [showConfirm, setShowConfirm] = useState(false)
  const [ackChecked, setAckChecked] = useState(false)
  // PERP 态:positionEffect/杠杆/保证金模式(默认 1x 逐仓;TradingPairInfo 无 maxLeverage,待接 CCXT 取)
  const [perpAction, setPerpAction] = useState<PerpAction>('OPEN_LONG')
  const [leverage, setLeverage] = useState(1)
  // 全仓后端未接,marginMode 固定 ISOLATED,UI 不暴露不可用的全仓选项(避免死控件)。
  const marginMode: 'ISOLATED' | 'CROSS' = 'ISOLATED'
  const submitMut = useSubmitOrder()
  const { data: balance } = useAccountBalance(accountId ?? undefined)

  const isPerp = marketType === 'PERP'
  // PERP 派生 side:OPEN_LONG/CLOSE_LONG → BUY(买入方向);OPEN_SHORT/CLOSE_SHORT → SELL
  const perpSide: 'BUY' | 'SELL' =
    perpAction === 'OPEN_LONG' || perpAction === 'CLOSE_LONG' ? 'BUY' : 'SELL'

  // 价格仅在页面加载/切标的/切市场类型时同步一次最新价,之后行情跳动不覆盖——用户要按那个价下单,
  // 框自己跳没法操作。synced 守一次;symbol 或 marketType 变 → reset,等首个 lastPrice 来时同步。
  // (切 SPOT↔PERP 同 symbol 价格可能不同,必须 reset 避免沿用上一市场类型价格)
  const synced = useRef(false)
  useEffect(() => {
    synced.current = false
  }, [symbol, marketType])
  useEffect(() => {
    if (!synced.current && lastPrice != null) {
      synced.current = true
      setPrice(String(lastPrice))
    }
  }, [lastPrice, symbol, marketType])

  // symbol 形如 BTC/USDT,拆出 base/quote(quote 即可用余额口径)。
  const [baseSym, quoteSym] = symbol.includes('/') ? symbol.split('/') : [symbol, 'USDT']
  const free = toDecimal(balance?.currencies?.[quoteSym]?.free ?? 0)
  const priceDec = toDecimal(price || '0')
  const qtyDec = toDecimal(qty || '0')
  // 市价类无价格输入,估算用最新成交价。
  const effPrice = MARKET_LIKE.includes(type) ? toDecimal(lastPrice ?? 0) : priceDec
  const notional = qtyDec.times(effPrice)
  const fee = notional.times(0.0004)

  // PERP 估算(decimal.js;强平价/保证金率/保证金占用,仅 PERP 态用)
  // 维持保证金率简化 0.5%(实际随档位变化,后端风控接真后改返回)
  const levDec = toDecimal(String(leverage))
  const mmrDec = toDecimal(String(MAINT_MARGIN_RATE))
  const isClose = perpAction.startsWith('CLOSE_')
  const isLongPos = perpAction === 'OPEN_LONG' || perpAction === 'CLOSE_LONG'
  // 保证金占用 = notional / leverage(开仓态;平仓态无新占用)
  const marginRequired = isPerp && !isClose && levDec.gt(0) ? notional.div(levDec) : toDecimal(0)
  // 强平价估算:开多 entry*(1-1/lev+mmr);开空 entry*(1+1/lev-mmr);平仓态不显
  const liquidationEst =
    isPerp && !isClose
      ? isLongPos
        ? effPrice.minus(effPrice.div(levDec)).plus(effPrice.times(mmrDec))
        : effPrice.plus(effPrice.div(levDec)).minus(effPrice.times(mmrDec))
      : toDecimal(0)
  // 保证金率 = 维持保证金 / 权益(原型用 mmr*lev 模拟权益占比,默认 100x → 50%)
  const marginRatioEst = isPerp && levDec.gt(0) ? mmrDec.times(levDec).toNumber() : 0

  /** 滑动条档位 → 按可用 quote 占比反算数量(限价用价格,市价类用最新价)。 */
  const applyPct = (v: number) => {
    setPct(v)
    if (v <= 0 || effPrice.lte(0)) {
      if (v <= 0) setQty('')
      return
    }
    const targetNotional = free.times(v).div(100)
    setQty(targetNotional.div(effPrice).toFixed(6))
  }

  const buildReq = (): OrderSubmitRequest => ({
    accountId: accountId ?? 0,
    symbol,
    // PERP 态 side 由 positionEffect 派生(OPEN_LONG/CLOSE_LONG→BUY,OPEN_SHORT/CLOSE_SHORT→SELL);
    // SPOT 用用户选的 BUY/SELL。
    side: isPerp ? perpSide : side,
    orderType: type,
    amount: qtyDec.toNumber(),
    price: MARKET_LIKE.includes(type) ? 0 : priceDec.toNumber(),
    stopPrice: (type.includes('STOP') || type.includes('TAKE_PROFIT')) && type !== 'TRAILING_STOP' ? toDecimal(stopPrice).toNumber() : 0,
    timeInForce: tif,
    expireAt: '', // GTD expireAt 在 doSubmit 算(Date.now() 移出 buildReq render,react-hooks/purity)
    clientOrderId: '',
    marketType,
    // PERP 透传:leverage/marginMode/positionEffect。SPOT 给零值(0/''/'')。
    // reduceOnly 不传(后端从 positionEffect=CLOSE_* 派生, 定案 3)——buildReq 不含该字段,
    // 类型 OrderSubmitRequest 也没 reduceOnly(那是 OrderDetailDto 的派生字段)。
    leverage: isPerp ? leverage : 0,
    marginMode: isPerp ? marginMode : '',
    positionEffect: isPerp ? perpAction : '',
  })

  // STOP/TAKE_PROFIT 类型(非 TRAILING_STOP)需触发价;空则禁用提交(避免 stopPrice=0 传后端被拒)
  const stopInvalid = (type.includes('STOP') || type.includes('TAKE_PROFIT')) && type !== 'TRAILING_STOP' && stopPrice === ''

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
    const req = buildReq()
    if (tif === 'GTD') {
      // eslint-disable-next-line react-hooks/purity -- doSubmit 是 onClick handler(L1082 confirm),非 render;Date.now() 在事件处理合法,purity 误判
      req.expireAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString()
    }
    submitMut.mutate(req, {
      onSuccess: (data) => {
        const perpLabel = PERP_ACTIONS.find((a) => a.key === perpAction)?.label
        toast.success(
          isLive ? (isPerp ? '实盘合约订单已提交' : '实盘订单已提交') : isPerp ? '合约订单已提交' : '订单已提交',
          {
            description: isPerp
              ? `${perpLabel} ${qty} ${symbol} · ${leverage}x ${marginMode === 'ISOLATED' ? '逐仓' : '全仓'} · orderId ${data.orderId ?? '-'}`
              : `${sideLabel(side)} ${qty} ${symbol} · orderId ${data.orderId ?? '-'}`,
          },
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
    <Card className="flex flex-col p-2.5">
      <div className="mb-1 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <strong className="text-body font-bold text-text-primary">下单</strong>
          {isLive ? (
            <span className="kq-live-badge">● 实盘</span>
          ) : (
            <span className="kq-paper-badge">模拟</span>
          )}
          {/* 现货/合约 segment:未选中也给底色(可点可见,非纯文字),active 实色填充 */}
          <div className="flex items-center gap-1">
            {(['SPOT', 'PERP'] as const).map((m) => {
              const active = marketType === m
              return (
                <button
                  key={m}
                  type="button"
                  onClick={() => onMarketTypeChange(m)}
                  className={cn(
                    'kq-press rounded-full px-2 py-0.5 text-caption font-bold tracking-[0.04em] transition-all',
                    active
                      ? 'bg-accent text-on-accent'
                      : 'bg-surface-card-2 text-text-muted hover:text-text-secondary',
                  )}
                >
                  {m === 'SPOT' ? '现货' : '合约'}
                </button>
              )
            })}
          </div>
        </div>
        {modeAccounts.length === 0 ? (
          <div className="w-full max-w-[220px]">
            <EmptyState
              title={isLive ? '还没有实盘账户' : '还没有模拟盘'}
              description="去添加账户开始交易"
              action={
                <Button size="sm" onClick={() => navigate('/settings?tab=accounts')}>
                  去添加
                </Button>
              }
            />
          </div>
        ) : (
          <Select
            value={accountId != null ? String(accountId) : undefined}
            onValueChange={(v) => {
              if (v === '__add_account__') {
                navigate('/settings?tab=accounts')
                return
              }
              onAccountChange(parseInt(v, 10))
            }}
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
              <SelectSeparator />
              <SelectItem value="__add_account__">+ 添加交易所账号</SelectItem>
            </SelectContent>
          </Select>
        )}
      </div>

      {/* 方向区:SPOT 买卖 / PERP 4 按钮(开多/开空/平多/平空),两态同套裸 button grid +
          active 实色填充+白字+glow(开仓)/平仓弱化(outline+soft 底)。不再用 Tabs 壳,SPOT/PERP 视觉一致。 */}
      {isPerp ? (
        <>
          {/* 4 按钮:红绿双色,平仓态弱化(outline+soft 底,无 glow);开仓态实色填充+白字+glow(强对比) */}
          <div className="mb-1 grid grid-cols-2 gap-1">
            {PERP_ACTIONS.map((a) => {
              const active = perpAction === a.key
              const colorVar = a.tone === 'up' ? 'var(--up)' : 'var(--down)'
              return (
                <button
                  key={a.key}
                  type="button"
                  onClick={() => setPerpAction(a.key)}
                  title={a.tag}
                  className={cn(
                    'kq-press rounded-lg border py-1.5 text-body font-bold tracking-[0.02em] transition-all',
                    !active && 'border-border-soft bg-surface-card-2 text-text-muted',
                  )}
                  style={
                    active
                      ? a.strong
                        ? { background: colorVar, borderColor: colorVar, color: 'var(--on-accent)' }
                        : { background: 'var(--surface-card-2)', borderColor: colorVar, color: colorVar }
                      : undefined
                  }
                >
                  {a.label}
                </button>
              )
            })}
          </div>

          {/* 杠杆:shadcn Slider(与数量滑块同款)+ 9 档预设。刻度走档位索引(0-8 等步进),
              修线性 range 滑距↔对数档位对不上 bug;thumb 位置与档位按钮一一对应。
              全仓后端未接(marginMode 固定 ISOLATED),不暴露不可用选项,避免死控件。 */}
          <div className="mb-1 rounded-lg border border-border-soft bg-surface-card-2 p-2">
            <div className="mb-1.5 flex items-center justify-between">
              <Label className="text-caption text-text-muted">杠杆</Label>
              <div className="flex items-center gap-1">
                {/* 外层 w-20 固定宽:Input 默认 w-full,在 flex 父里会循环依赖塌缩到 min-content
                    (多位数字只显首字符 10→1/25→2);包固定宽 div 让 w-full 填 80px 不塌缩。
                    type=text:text-right 对 type=number 不生效(浏览器默认 LTR),改 text 显完整数字;无 spinner。 */}
                <div className="w-20">
                  <Input
                    type="text"
                    inputMode="numeric"
                    value={String(leverage)}
                    onChange={(e) => {
                      const v = parseInt(e.target.value || '1', 10)
                      setLeverage(Math.max(LEVERAGE_MIN, Math.min(LEVERAGE_MAX, Number.isNaN(v) ? 1 : v)))
                    }}
                    className="kq-mono-row h-7 px-2 text-right text-caption"
                  />
                </div>
                <span className="text-caption font-semibold text-text-muted">x</span>
              </div>
            </div>
            <Slider
              value={[leverage]}
              min={LEVERAGE_MIN}
              max={LEVERAGE_MAX}
              step={1}
              onValueChange={(v) => setLeverage(Math.max(LEVERAGE_MIN, Math.min(LEVERAGE_MAX, v[0] ?? LEVERAGE_MIN)))}
              aria-label="杠杆倍数"
            />
            {/* 档位 segmented control:9 段 flex-1 等宽撑满整条(无右侧空白),连成一体;
                active 段实色橙填充,inactive 灰底灰字;段间 border-l divider 分隔。
                比独立按钮+右空白更有整体设计感(iOS/OKX 订单类型 tab 同款模式)。 */}
            <div className="mt-1 flex rounded-md border border-border-soft bg-surface-card-2 p-0.5">
              {LEVERAGE_PRESETS.map((p, i) => {
                const active = leverage === p
                return (
                  <button
                    key={p}
                    type="button"
                    onClick={() => setLeverage(p)}
                    className={cn(
                      'kq-press flex-1 rounded-sm py-1 text-[10px] font-bold transition-all',
                      i > 0 && 'border-l border-border-soft',
                      active
                        ? 'bg-accent text-on-accent'
                        : 'text-text-muted hover:text-text-secondary',
                    )}
                  >
                    {p}x
                  </button>
                )
              })}
            </div>
          </div>
        </>
      ) : (
        /* SPOT 买卖:与 PERP 4 按钮同套裸 button grid(active 实色 up/down + 白字 + glow),不再用 Tabs 壳。 */
        <div className="mb-1 grid grid-cols-2 gap-1">
          {([
            { key: 'BUY' as const, label: '买入', tone: 'up' as const },
            { key: 'SELL' as const, label: '卖出', tone: 'down' as const },
          ]).map((a) => {
            const active = side === a.key
            const colorVar = a.tone === 'up' ? 'var(--up)' : 'var(--down)'
            return (
              <button
                key={a.key}
                type="button"
                onClick={() => setSide(a.key)}
                className={cn(
                  'kq-press rounded-lg border py-1.5 text-body font-bold tracking-[0.02em] transition-all',
                  !active && 'border-border-soft bg-surface-card-2 text-text-muted',
                )}
                style={
                  active
                    ? { background: colorVar, borderColor: colorVar, color: 'var(--on-accent)' }
                    : undefined
                }
              >
                {a.label}
              </button>
            )
          })}
        </div>
      )}

      {/* 委托类型 TIF 下拉(去 Label 省 17px;aria-label 补 a11y) */}
      <div className="mb-1">
        <Select value={tif} onValueChange={(v) => setTif(v as (typeof TIF)[number])}>
          <SelectTrigger size="sm" className="h-8 w-full text-body-sm" aria-label="委托类型">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {TIF.map((t) => (
              <SelectItem key={t} value={t}>
                {t}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 价格 + 下单类型(同行,去 Label 用 placeholder 省 17px;aria-label 补 a11y) */}
      <div className="mb-1 grid grid-cols-2 gap-2">
        <Input
          className="kq-mono-row h-8"
          value={price}
          inputMode="decimal"
          onChange={(e) => setPrice(e.target.value)}
          disabled={MARKET_LIKE.includes(type)}
          placeholder={`价格 ${quoteSym}`}
          aria-label={`价格 ${quoteSym}`}
          style={{ opacity: MARKET_LIKE.includes(type) ? 0.5 : 1 }}
        />
        <Select value={type} onValueChange={(v) => setType(v as (typeof ORDER_TYPES)[number])}>
          <SelectTrigger size="sm" className="h-8 w-full text-body-sm" aria-label="下单类型">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ORDER_TYPES.map((t) => (
              <SelectItem key={t} value={t}>
                {orderTypeLabelCn(t)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 触发价 / 追踪幅度(按订单类型条件显示,替代写死布局) */}
      {type === 'TRAILING_STOP' && (
        <Input className="kq-mono-row mb-1 h-8" value={trail} inputMode="decimal" onChange={(e) => setTrail(e.target.value)} placeholder="追踪幅度 %" aria-label="追踪幅度百分比" />
      )}
      {(type.includes('STOP') || type.includes('TAKE_PROFIT')) && type !== 'TRAILING_STOP' && (
        <Input className="kq-mono-row mb-1 h-8" value={stopPrice} inputMode="decimal" onChange={(e) => setStopPrice(e.target.value)} placeholder={`触发价 ${quoteSym}`} aria-label={`触发价 ${quoteSym}`} />
      )}

      {/* 数量(去 Label,placeholder 内联) */}
      <Input className="kq-mono-row mb-1 h-8" value={qty} inputMode="decimal" onChange={(e) => setQty(e.target.value)} placeholder={`数量 ${baseSym}`} aria-label={`数量 ${baseSym}`} />

      {/* 数量比例:Slider + 5 档下方 justify-between(按钮中心 idx/4 对齐 thumb pct%);按可用金额反算数量 */}
      <div className="mb-1">
        <Slider
          value={[pct]}
          onValueChange={(v) => applyPct(v[0] ?? 0)}
          min={0}
          max={100}
          step={1}
          aria-label="按可用金额比例快速设置数量"
        />
        <div className="mt-1 flex justify-between text-[10px]">
          {[0, 25, 50, 75, 100].map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => applyPct(p)}
              className={cn('kq-press w-8 text-center', pct === p ? 'font-bold text-accent' : 'text-text-muted')}
            >
              {p === 0 ? '0' : `${p}%`}
            </button>
          ))}
        </div>
      </div>

      {/* 底部信息行(精简):可用 + [PERP]预估保证金占用 + 订单金额;
          强平价/保证金率/手续费移 hover title(PERP 风险在确认 Dialog 详述),省 3 行高。 */}
      <div className="mb-1 rounded-md bg-surface-card-2 p-1.5">
        <div className="flex justify-between text-caption text-text-muted">
          <span>可用 {quoteSym}</span>
          <span className="kq-mono-row">{formatMoney(free, { dp: 2 })}</span>
        </div>
        {isPerp && !isClose && (
          <div
            className="mt-0.5 flex justify-between text-caption text-text-muted"
            title={`强平价(估) ${formatMoney(liquidationEst, { dp: 2 })} · 保证金率(估) ${(marginRatioEst * 100).toFixed(2)}%`}
          >
            <span>预估保证金占用</span>
            <span className="kq-mono-row font-bold text-text-primary">
              {formatMoney(marginRequired, { dp: 2 })} {quoteSym}
            </span>
          </div>
        )}
        <div
          className="mt-0.5 flex justify-between text-caption text-text-muted"
          title={`预估手续费 ${formatMoney(fee, { dp: 4 })} ${quoteSym}`}
        >
          <span>订单金额</span>
          <span className="kq-mono-row font-bold text-text-primary">
            {formatMoney(notional, { dp: 2 })} {quoteSym}
          </span>
        </div>
      </div>

      <button
        type="button"
        onClick={submit}
        disabled={submitMut.isPending || stopInvalid}
        title={stopInvalid ? '请填触发价' : undefined}
        className="kq-press w-full rounded-md p-2.5 text-body font-bold text-on-accent transition-all disabled:opacity-50"
        style={{
          background: isPerp
            ? isLongPos
              ? 'var(--up)'
              : 'var(--down)'
            : side === 'BUY'
              ? 'var(--up)'
              : 'var(--down)',
          cursor: 'pointer',
        }}
      >
        {isPerp
          ? `${PERP_ACTIONS.find((a) => a.key === perpAction)?.label} ${qty || '0'} ${symbol}${isPerp ? ' 合约' : ''} · ${leverage}x`
          : `${sideLabel(side)} ${qty || '0'} ${symbol}`}
        {isLive && ' · 真金白银'}
      </button>

      {/* 风险提示移到 LIVE 确认 Dialog(下方)+ CTA 文案「真金白银」;删卡内提示省垂直空间 */}

      {/* LIVE 下单确认 Dialog + Checkbox(PERP 适配:显示方向+杠杆+保证金模式+强平风险) */}
      <Dialog open={showConfirm} onOpenChange={(o) => { setShowConfirm(o); if (!o) setAckChecked(false) }}>
        <DialogContent className="max-w-[460px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="size-4 text-down" aria-hidden />
              {isPerp ? '实盘合约下单确认' : '实盘下单确认'}
            </DialogTitle>
            <DialogDescription>
              {isPerp ? '实盘合约 · 真实资金 · 带杠杆,存在强平风险,请仔细确认参数。' : '实盘订单 · 真实资金 · 请仔细确认参数。'}
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div className="rounded-md border border-accent bg-accent-soft p-3.5">
              <div className="text-body-sm font-bold text-accent">这是实盘{isPerp ? '合约' : ''}订单</div>
              <div className="mt-1 text-caption leading-relaxed text-accent">
                下单用真实资金,会产生真实手续费{isPerp ? ',带杠杆,亏损可能超过保证金,存在强平风险' : ''}。
              </div>
            </div>
            <div className="rounded-md border border-border-soft bg-surface-card-2 p-3.5">
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">市场</span>
                <strong>{isPerp ? '合约' : '现货'}</strong>
              </div>
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">订单类型</span>
                <strong>{orderTypeLabelCn(type)}</strong>
              </div>
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">方向</span>
                <span
                  className={
                    isPerp
                      ? isLongPos
                        ? 'text-up'
                        : 'text-down'
                      : side === 'BUY'
                        ? 'text-up'
                        : 'text-down'
                  }
                >
                  {isPerp ? PERP_ACTIONS.find((a) => a.key === perpAction)?.label : sideLabel(side)}
                </span>
              </div>
              {isPerp && (
                <div className="flex justify-between py-1 text-body-sm">
                  <span className="text-text-muted">杠杆 · 保证金</span>
                  <span className="kq-mono-row font-bold">
                    {leverage}x · {marginMode === 'ISOLATED' ? '逐仓' : '全仓'}
                  </span>
                </div>
              )}
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">价格</span>
                <span className="kq-mono-row">{MARKET_LIKE.includes(type) ? '市价' : price}</span>
              </div>
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">数量</span>
                <span className="kq-mono-row">
                  {qty} {baseSym}
                  {isPerp ? ' · 合约' : ''}
                </span>
              </div>
              {isPerp && (
                <div className="flex justify-between py-1 text-body-sm">
                  <span className="text-text-muted">预估保证金占用</span>
                  <span className="kq-mono-row font-bold">{formatMoney(marginRequired, { dp: 2 })} {quoteSym}</span>
                </div>
              )}
              <div className="flex justify-between py-1 text-body-sm">
                <span className="text-text-muted">总金额</span>
                <span className="kq-mono-row font-bold">{formatMoney(notional, { dp: 2 })} {quoteSym}</span>
              </div>
            </div>
            <label className="flex items-start gap-2 text-body-sm text-text-secondary">
              <Checkbox checked={ackChecked} onCheckedChange={(v) => setAckChecked(v === true)} />
              <span>
                我已确认这是实盘{isPerp ? '合约' : ''}订单{isPerp ? ',知悉杠杆与强平风险' : ',知悉风险'}
              </span>
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
              style={{
                background: isPerp ? (isLongPos ? 'var(--up)' : 'var(--down)') : (side === 'BUY' ? 'var(--up)' : 'var(--down)'),
                border: 'none',
                cursor: 'pointer',
              }}
            >
              确认下单(真金白银)
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

/** PositionsTable — 单账户持仓(uPnl 用 PositionDto.unrealizedPnl,行情不可用 null 显 —)。
 *  合约列 port(照原型 done-design/TradingPage.jsx PositionsTable):
 *  - PERP 态(positionSide 非空)显 杠杆/保证金模式/标记价/强平价 列;SPOT 态显 —
 *  - 方向列:PERP 按 positionSide 显 多/空;SPOT 按 side 显 多/空/空(中文,不暴露枚举字面量)
 *  - 平仓按钮:PERP 显 平多/平空(按 positionSide),SPOT 显 平仓;调 useClosePosition(positionId)。
 *    后端 PositionController.close 按 pos.marketType 派生 positionEffect,前端只传 positionId。
 *  - markPrice:PositionDto.currentPrice 契约标"当前市价",即 markPrice。
 *  - 强平价:PositionDto.liquidationPrice(PERP 逐仓,SPOT null/0 显 —)。
 */
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
  // 任意一个持仓是 PERP(positionSide 非空 LONG/SHORT)→ 表头显合约列(对齐 3.3 原型 hasPerp 判定)
  const hasPerp = list.some(
    (p) => p.positionSide === 'LONG' || p.positionSide === 'SHORT',
  )
  const colSpan = hasPerp ? 12 : 8
  return (
    <Card className="p-5">
      <SectionTitle
        title="持仓"
        sub={isLive ? '实盘持仓' : '模拟盘持仓'}
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
              {hasPerp && <TableHead className="px-3 py-2 text-right">杠杆</TableHead>}
              {hasPerp && <TableHead className="px-3 py-2">保证金</TableHead>}
              {hasPerp && <TableHead className="px-3 py-2 text-right">标记价</TableHead>}
              {hasPerp && <TableHead className="px-3 py-2 text-right">强平价</TableHead>}
              {hasPerp ? (
                <>
                  <TableHead className="px-3 py-2 text-right">未实现 (USDT)</TableHead>
                  <TableHead className="px-3 py-2 text-right">已实现 (USDT)</TableHead>
                </>
              ) : (
                <TableHead className="px-3 py-2 text-right" colSpan={2}>浮动盈亏 (USDT)</TableHead>
              )}
              <TableHead className="px-3 py-2 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="kq-mono-row">
            {isLoading ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={colSpan} className="p-6">
                  <LoadingState />
                </TableCell>
              </TableRow>
            ) : list.length === 0 ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={colSpan} className="p-6">
                  <EmptyState title="无持仓" description="当前账户无持仓" />
                </TableCell>
              </TableRow>
            ) : (
              list.map((p) => {
                // isPerp 判定:positionSide 非空即合约持仓(SPOT positionSide 为 '')。
                const isPerp = p.positionSide === 'LONG' || p.positionSide === 'SHORT'
                // 方向:PERP 按 positionSide,SPOT 按 side(LONG/SHORT/FLAT)→ 中文 多/空/空
                const dirEnum = isPerp ? p.positionSide : p.side // 'LONG' | 'SHORT' | 'FLAT' | ''
                const isLong = dirEnum === 'LONG'
                const isShort = dirEnum === 'SHORT'
                const dirLabelCn = isLong ? '多' : isShort ? '空' : '—'
                const dirToneClass = isLong ? 'text-up' : isShort ? 'text-down' : 'text-text-muted'
                const rPnl = toDecimal(p.realizedPnl)
                // unrealizedPnl 契约标 number 但运行时可 null(行情不可用),cast 守
                const uPnl = p.unrealizedPnl as number | null
                const uPnlNull = uPnl == null
                // markPrice:PositionDto.currentPrice(当前市价,即 markPrice 估;null 显 —)
                const markPrice = p.currentPrice as number | null
                // 强平价:PERP 逐仓有值,SPOT null/0 显 —
                const liqPrice = p.liquidationPrice as number | null
                const liqShown = isPerp && liqPrice != null && liqPrice !== 0
                // 平仓按钮文案:PERP 按 positionSide 显 平多/平空;SPOT 显 平仓
                const closeLabel = isPerp
                  ? p.positionSide === 'LONG'
                    ? '平多'
                    : '平空'
                  : '平仓'
                return (
                  <TableRow key={p.positionId}>
                    <TableCell className="px-3 py-2.5">
                      {isLive ? <span className="kq-live-badge">● 实盘</span> : <span className="kq-paper-badge">模拟</span>}
                    </TableCell>
                    <TableCell className="px-3 py-2.5">
                      {p.symbol}
                      {isPerp ? (
                        <span className="ml-1.5 rounded-[4px] bg-accent-soft px-1.5 py-px text-[9.5px] font-bold tracking-[0.04em] text-accent">
                          合约
                        </span>
                      ) : (
                        <span className="ml-1.5 rounded-[4px] bg-surface-3 px-1.5 py-px text-[9.5px] font-bold tracking-[0.04em] text-text-muted">
                          现货
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="px-3 py-2.5">
                      <span className={`font-bold ${dirToneClass}`}>
                        {dirLabelCn}
                      </span>
                    </TableCell>
                    <TableCell className="px-3 py-2.5 text-right">{formatMoney(toDecimal(p.qty), { dp: 4 })}</TableCell>
                    <TableCell className="px-3 py-2.5 text-right">{formatMoney(toDecimal(p.avgEntryPrice), { dp: 2 })}</TableCell>
                    {hasPerp && (
                      <TableCell className="px-3 py-2.5 text-right text-text-muted">
                        {isPerp ? `${p.leverage}x` : '—'}
                      </TableCell>
                    )}
                    {hasPerp && (
                      <TableCell className={`px-3 py-2.5 ${isPerp ? 'text-text-secondary' : 'text-text-muted'}`}>
                        {isPerp ? (p.marginMode === 'ISOLATED' ? '逐仓' : p.marginMode === 'CROSS' ? '全仓' : p.marginMode || '—') : '—'}
                      </TableCell>
                    )}
                    {hasPerp && (
                      <TableCell className="px-3 py-2.5 text-right text-text-secondary">
                        {isPerp && markPrice != null ? formatMoney(toDecimal(markPrice), { dp: 2 }) : '—'}
                      </TableCell>
                    )}
                    {hasPerp && (
                      <TableCell className={`px-3 py-2.5 text-right ${liqShown ? 'font-bold text-warning' : 'text-text-muted'}`}>
                        {liqShown ? formatMoney(toDecimal(liqPrice), { dp: 2 }) : '—'}
                      </TableCell>
                    )}
                    {hasPerp ? (
                      <>
                        <TableCell className={`px-3 py-2.5 text-right ${uPnlNull ? 'text-text-muted' : pnlTextClass(toDecimal(uPnl).toNumber())}`}>
                          {uPnlNull ? '—' : <>{pnlArrow(toDecimal(uPnl).toNumber())}{formatMoney(toDecimal(uPnl).abs(), { dp: 2 })} USDT</>}
                        </TableCell>
                        {isPerp ? (
                          <TableCell className={`px-3 py-2.5 text-right ${pnlTextClass(rPnl.toNumber())}`}>
                            {pnlArrow(rPnl.toNumber())}{formatMoney(rPnl.abs(), { dp: 2 })} USDT
                          </TableCell>
                        ) : (
                          <TableCell className="px-3 py-2.5 text-right text-text-muted">—</TableCell>
                        )}
                      </>
                    ) : (
                      <TableCell className={`px-3 py-2.5 text-right ${uPnlNull ? 'text-text-muted' : pnlTextClass(toDecimal(uPnl).toNumber())}`} colSpan={2}>
                        {uPnlNull ? '—' : <>{pnlArrow(toDecimal(uPnl).toNumber())}{formatMoney(toDecimal(uPnl).abs(), { dp: 2 })} USDT</>}
                      </TableCell>
                    )}
                    <TableCell className="px-3 py-2.5 text-right">
                      <Button variant="ghost" size="sm" onClick={() => onClose(p)}>
                        {closeLabel}
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

/** OrdersTable — 当前订单(useOrders + normalizeOrderStatus)。 */
function OrdersTable({ accountId, isLive }: { accountId: number | null; isLive: boolean }) {
  // 状态过滤:活动=挂单中四态(PENDING_NEW/SUBMITTED/PARTIALLY_FILLED/PENDING_CANCEL,与后端
  //   OrderMapper.findActiveByAccount 权威定义一致);已撤销=CANCELLED;全部=不过滤。
  // ⚠️ status 值必须是后端 OrderStatus enum 合法名:OrderController.parseStatuses 用
  //   OrderStatus.valueOf 严格解析,非法名(如旧值的 'PARTIAL',enum 实为 PARTIALLY_FILLED)
  //   → IllegalArgumentException → 400(4103)→ useOrders 失败 → 活动 tab 永空。PERP 限价
  //   挂单停在 SUBMITTED 才暴露此 bug(SPOT 即时 FILLED,活动 tab 本就空没撞到)。
  // 可撤态:仅 SUBMITTED/PARTIALLY_FILLED(后端 OrderStatus.ALLOWED 只此二态可转 PENDING_CANCEL)。
  // NEW/PENDING_NEW 提交前瞬态、PENDING_CANCEL 撤单中——后端 TradingService.cancel 对非法转换静默
  // return 不抛 4101,前端收紧白名单避免假成功 toast(显示"处理中")。
  // terminal(FILLED/CANCELLED/REJECTED/EXPIRED)显 —。useCancelOrder DELETE /orders/{id}。
  const CANCELABLE: ReadonlySet<string> = new Set(['SUBMITTED', 'PARTIALLY_FILLED'])
  const CANCEL_TERMINAL: ReadonlySet<string> = new Set(['FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED'])
  const cancelMut = useCancelOrder()
  const [cancelTarget, setCancelTarget] = useState<OrderDetailDto | null>(null)
  // 成交明细展开:点订单 ID toggle,展开显 useOrderFills(订单成交明细)
  const [expandedOrderId, setExpandedOrderId] = useState<number | null>(null)
  const [filter, setFilter] = useState<'active' | 'cancelled' | 'all'>('active')
  const status = filter === 'active'
    ? 'PENDING_NEW,SUBMITTED,PARTIALLY_FILLED,PENDING_CANCEL'
    : filter === 'cancelled'
      ? 'CANCELLED'
      : undefined
  const { data, isLoading, error } = useOrders(accountId, { pageSize: 50, status })
  const page = data?.content ?? []
  const orderTabs: { key: 'active' | 'cancelled' | 'all'; label: string }[] = [
    { key: 'active', label: '活动' },
    { key: 'all', label: '全部' },
    { key: 'cancelled', label: '已撤销' },
  ]
  return (
    <Card className="p-5">
      <SectionTitle
        title="当前订单"
        sub={isLive ? '实盘挂单 · 部分成交' : '模拟盘挂单 · 部分成交'}
        right={
          <div className="flex gap-1.5">
            {orderTabs.map((t) => {
              const active = filter === t.key
              return (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setFilter(t.key)}
                  className={cn(
                    'kq-press rounded-md border px-2 py-1 text-caption transition-all',
                    active
                      ? 'border-accent bg-surface-card text-accent'
                      : 'border-border-soft bg-surface-card-2 text-text-muted hover:text-text-primary',
                  )}
                >
                  {t.label}
                </button>
              )
            })}
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
              <TableHead className="px-3 py-2 text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="kq-mono-row">
            {isLoading ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={9} className="p-6">
                  <LoadingState />
                </TableCell>
              </TableRow>
            ) : error ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={9} className="p-6">
                  <ErrorState message={(error as Error).message} />
                </TableCell>
              </TableRow>
            ) : page.length === 0 ? (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={9} className="p-6">
                  <EmptyState
                    title={filter === 'active' ? '无活动订单' : filter === 'cancelled' ? '无已撤销订单' : '无订单'}
                    description={
                      filter === 'active'
                        ? '当前没有活动中的挂单'
                        : filter === 'cancelled'
                          ? '当前没有已撤销订单'
                          : '当前账户无订单'
                    }
                  />
                </TableCell>
              </TableRow>
            ) : (
              page.map((o: OrderDetailDto) => {
                const isBuy = o.side.toUpperCase() === 'BUY'
                const isOpen = expandedOrderId === o.orderId
                return (
                  <Fragment key={o.orderId}>
                    <TableRow>
                      <TableCell className="px-3 py-2.5">
                        <button
                          type="button"
                          onClick={() => setExpandedOrderId(isOpen ? null : o.orderId)}
                          className="font-mono text-text-secondary hover:text-text-primary"
                          aria-label={isOpen ? '收起成交明细' : '展开成交明细'}
                          aria-expanded={isOpen}
                        >
                          {o.orderId} <span className="text-text-muted">{isOpen ? '▾' : '▸'}</span>
                        </button>
                      </TableCell>
                    <TableCell className="px-3 py-2.5">{o.symbol}</TableCell>
                    <TableCell className="px-3 py-2.5">
                      <Chip label={orderTypeLabelCn(o.orderType)} />
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
                      {o.createdAt ? formatDateTime(o.createdAt, 'MM-dd HH:mm') : '—'}
                    </TableCell>
                    <TableCell className="px-3 py-2.5 text-right">
                      {CANCELABLE.has(o.status) ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-down hover:text-down"
                          disabled={cancelMut.isPending && cancelTarget?.orderId === o.orderId}
                          onClick={() => setCancelTarget(o)}
                        >
                          撤单
                        </Button>
                      ) : CANCEL_TERMINAL.has(o.status) ? (
                        <span className="text-text-muted">—</span>
                      ) : (
                        <span className="text-caption text-text-muted">处理中</span>
                      )}
                    </TableCell>
                  </TableRow>
                    {isOpen && <FillsRow orderId={o.orderId} />}
                  </Fragment>
                )
              })
            )}
          </TableBody>
        </Table>
      </div>
      <ConfirmDialog
        open={cancelTarget != null}
        onOpenChange={(o) => {
          if (!o) setCancelTarget(null)
        }}
        title="确认撤销订单"
        description={`撤销订单 #${cancelTarget?.orderId ?? ''}(${cancelTarget ? sideLabel(cancelTarget.side) : ''} ${cancelTarget?.symbol ?? ''} ${cancelTarget ? orderTypeLabelCn(cancelTarget.orderType) : ''})。撤销后不可恢复。`}
        confirmLabel={cancelMut.isPending ? '撤销中…' : '撤销'}
        destructive
        loading={cancelMut.isPending}
        onConfirm={() => {
          if (!cancelTarget || cancelMut.isPending) return
          cancelMut.mutate(cancelTarget.orderId, {
            onSuccess: () => {
              toast.success('已撤销', { description: `订单 #${cancelTarget.orderId} 已撤销` })
              setCancelTarget(null)
            },
            onError: (e) => {
              toast.error('撤销失败', { description: (e as Error).message })
            },
          })
        }}
      />
    </Card>
  )
}

/** FillsRow — 订单成交明细展开行(useOrderFills)。点订单 ID toggle 展开,显 FillDto 列表。 */
function FillsRow({ orderId }: { orderId: number }) {
  const { data: fills, isLoading, isError } = useOrderFills(orderId)
  return (
    <TableRow className="hover:bg-transparent">
      <TableCell colSpan={9} className="border-t border-border-soft p-3 pl-6">
        {isLoading ? (
          <LoadingState rows={2} />
        ) : isError ? (
          <div className="py-2 text-center text-caption text-down">成交明细加载失败,请重试</div>
        ) : (fills ?? []).length === 0 ? (
          <div className="py-2 text-center text-caption text-text-muted">无成交明细</div>
        ) : (
          <div className="flex flex-col gap-1.5">
            {(fills ?? []).map((f) => (
              <div key={f.fillId} className="flex items-center justify-between gap-3 text-caption kq-mono-row">
                <span className="text-text-muted">{f.filledAt ? formatDateTime(f.filledAt, 'MM-dd HH:mm') : '—'}</span>
                <span className={f.liquidity === 'TAKER' ? 'text-warning' : 'text-text-secondary'}>
                  {f.liquidity === 'TAKER' ? '吃单' : '挂单'}
                </span>
                <span className="text-text-secondary">{formatMoney(toDecimal(f.price), { dp: 2 })}</span>
                <span>{formatMoney(toDecimal(f.qty), { dp: 4 })}</span>
                <span className="text-text-muted">手续费 {formatMoney(toDecimal(f.fee), { dp: 4 })} {f.feeCurrency}</span>
              </div>
            ))}
          </div>
        )}
      </TableCell>
    </TableRow>
  )
}
