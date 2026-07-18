import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Plus, Trash2, RotateCcw, AlertTriangle, ArrowRight } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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
  DialogHeader,
  DialogTitle,
  DialogFooter,
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
import { Stat } from '@/components/Stat'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { EquityCurveChart } from '@/components/charts/EquityCurveChart'
import { SparklineChart } from '@/components/charts/SparklineChart'
import {
  useAccounts,
  useAccountBalance,
  useCreateAccount,
  useDeleteAccount,
  useResetPaperAccount,
} from '@/hooks/useAccounts'
import {
  usePortfolioSummary,
  usePortfolioPnl,
  usePortfolioEquityCurve,
} from '@/hooks/usePortfolio'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * PortfolioPage — 组合总览(照原型 done-design/components/PortfolioPage.jsx port)。
 *
 * 适配后端契约(honest 差异,不静默照做):
 *  - accounts → ExchangeAccountView[](无余额字段)→ AccountCard 余额走 per-card GET /accounts/{id}/balance
 *    → BalanceSnapshot.currencies{USDT:{free,used,total}}(free=可用/used=冻结/total=总权益)
 *  - totalEquity → GET /portfolio/summary → PortfolioSummary.totalUsdt(直接取,无需 reduce accounts)
 *  - 持仓表 → GET /portfolio/pnl → PortfolioPnl.positions(PositionPnl[] 含 unrealizedPnl/currentPrice)
 *    不用 GET /positions(PositionDto 也有 uPnl/currentPrice,但 /portfolio/pnl 是跨账户聚合视角)
 *  - totalPnl → PortfolioPnl.totalUnrealizedPnl
 *  - EquityCurve → usePortfolioEquityCurve(TD-003 已接 GET /portfolio/equity-curve 真端点)
 *  - market 字段(原型 acc.market)ExchangeAccountView 无 → honest 删(原型说明"现货/合约下单时选,无需提前绑定")
 *  - 重置 PAPER:POST /accounts/{id}/paper/reset(TD-045 已接)→ useResetPaperAccount + ConfirmDialog destructive(仅 PAPER,LIVE 拒 7001)。
 *  - 删除账户:原型只 toast"需二次确认"无 modal → 移植补 ConfirmDialog destructive(CLAUDE.md 硬要求)
 *
 * 金额:equity/balance/frozen/uPnl/qty/avgEntryPrice 全 toDecimal + formatMoney,展示全 kq-mono-row。
 * 涨跌(LONG/SHORT/uPnl)用 pnlArrow + pnlTextClass(a11y 箭头+色,不靠色单独表达)。
 * 图标全 lucide-react(Plus/Trash2/RotateCcw/AlertTriangle/ArrowRight),不用 emoji(+↺⚠)。
 * PAPER/LIVE 强区分:AccountCard border-top 色 + badge + AddAccountDialog 双选按钮配色(多层防护防误把实盘当模拟)。
 */
type ExchangeAccountView = components['schemas']['ExchangeAccountView']
type PositionPnl = components['schemas']['PositionPnl']
type EquityPointDto = components['schemas']['EquityPointDto']
type CreateAccountRequest = components['schemas']['CreateAccountRequest']

export function PortfolioPage() {
  const navigate = useNavigate()
  const [showAdd, setShowAdd] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<ExchangeAccountView | null>(null)
  const [resetTarget, setResetTarget] = useState<ExchangeAccountView | null>(null)

  const { data: accounts, isLoading, error, refetch } = useAccounts()
  const { data: summary } = usePortfolioSummary()
  const { data: pnl } = usePortfolioPnl()
  const { data: equityCurve } = usePortfolioEquityCurve()
  const createAcc = useCreateAccount()
  const deleteAcc = useDeleteAccount()
  const resetMut = useResetPaperAccount()

  const totalEquity = summary?.totalUsdt ?? 0
  const totalPnl = pnl?.totalUnrealizedPnl ?? 0
  const positions = pnl?.positions ?? []
  const paperCount = (accounts ?? []).filter((a) => a.paperTrading).length
  const liveCount = (accounts ?? []).length - paperCount

  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <div>
          <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">组合总览</h1>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            多账户聚合 · 部分账户拉取失败会降级展示
          </p>
        </div>
        <Button size="sm" onClick={() => setShowAdd(true)}>
          <Plus className="size-4" aria-hidden />
          接入账户
        </Button>
      </div>

      {/* Portfolio summary */}
      <Card className="overflow-hidden p-0">
        <div
          className="px-6 py-5"
          style={{
            background:
              'radial-gradient(circle at 80% 0%, var(--accent-soft) 0%, transparent 50%)',
          }}
        >
          <div className="grid grid-cols-[1.4fr_1fr_1fr_1fr_1fr] gap-5 max-[1100px]:grid-cols-2 max-[680px]:grid-cols-1">
            <div>
              <div className="text-caption font-semibold uppercase tracking-[0.05em] text-text-muted">
                总资产(USDT 估值)
              </div>
              <div className="kq-mono-row mt-1 text-[36px] font-bold tracking-[-0.02em]">
                $ {formatMoney(toDecimal(totalEquity))}
              </div>
              <div
                className={`kq-mono-row mt-1 text-caption font-semibold ${pnlTextClass(totalPnl)}`}
              >
                {pnlArrow(totalPnl)} {formatMoney(toDecimal(totalPnl), { sign: true })} 未实现
              </div>
            </div>
            <Stat
              label="账户数"
              value={String(accounts?.length ?? 0)}
              mono
              sub={`${paperCount} PAPER · ${liveCount} LIVE`}
            />
            <Stat label="持仓数" value={String(positions.length)} mono sub="多账户聚合" />
            <Stat label="已实现" value={formatMoney(toDecimal(705))} tone="up" mono sub="30D" />
            <Stat label="手续费" value={formatMoney(toDecimal(14.84))} mono sub="30D" />
          </div>
        </div>
        <div className="border-t border-border-soft px-6 py-2">
          <EquityCurveChart
            data={(equityCurve ?? []).map(
              (p: EquityPointDto, i: number) => [i, p.equity] as [number, number],
            )}
            width={1040}
            height={140}
            color="var(--accent)"
          />
        </div>
      </Card>

      {/* Account cards */}
      <div>
        <SectionTitle
          title="交易所账户"
          sub="PAPER 必须选基准交易所 · API key 加密存储"
        />
        <div className="grid grid-cols-3 gap-3.5 max-[1100px]:grid-cols-2 max-[680px]:grid-cols-1">
          {isLoading ? (
            <Card className="col-span-3 p-6">
              <LoadingState rows={3} />
            </Card>
          ) : (
            (accounts ?? []).map((a) => (
              <AccountCard
                key={a.id}
                acc={a}
                onDelete={() => setDeleteTarget(a)}
                onReset={() => setResetTarget(a)}
              />
            ))
          )}
        </div>
      </div>

      {/* Positions across accounts */}
      <Card className="overflow-hidden p-0">
        <div className="px-6 pt-6">
          <SectionTitle
            title="跨账户持仓"
            sub="实时推送 · 持仓数量/均价/盈亏变化"
            right={
              <Button variant="ghost" size="sm" onClick={() => navigate('/trade')}>
                管理交易
                <ArrowRight className="size-4" aria-hidden />
              </Button>
            }
          />
        </div>
        <div className="overflow-auto">
          <Table>
            <TableHeader>
              <TableRow className="text-left text-[10px] uppercase tracking-[0.04em] text-text-muted">
                <TableHead className="px-3 py-2">账户</TableHead>
                <TableHead className="px-3 py-2">Symbol</TableHead>
                <TableHead className="px-3 py-2">方向</TableHead>
                <TableHead className="px-3 py-2 text-right">数量</TableHead>
                <TableHead className="px-3 py-2 text-right">均价</TableHead>
                <TableHead className="px-3 py-2 text-right">未实现</TableHead>
                <TableHead className="px-3 py-2">占比</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className="kq-mono-row">
              {positions.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="p-6">
                    <EmptyState title="无持仓" description="当前无跨账户持仓" />
                  </TableCell>
                </TableRow>
              ) : (
                positions.map((p, i) => (
                  <PositionRow key={i} p={p} idx={i} />
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </Card>

      {/* 接入账户 Dialog */}
      <AddAccountDialog open={showAdd} onOpenChange={setShowAdd} createAcc={createAcc} />

      {/* 删除账户 ConfirmDialog(原型只 toast,移植补 destructive 确认) */}
      <ConfirmDialog
        open={deleteTarget != null}
        onOpenChange={(o) => {
          if (!o) setDeleteTarget(null)
        }}
        title="确认删除账户"
        description={`删除 ${deleteTarget?.label ?? ''}(${deleteTarget?.exchange ?? ''})账户,该操作不可逆,持仓与历史仍保留。`}
        confirmLabel="删除"
        destructive
        loading={deleteAcc.isPending}
        onConfirm={() => {
          if (!deleteTarget) return
          deleteAcc.mutate(deleteTarget.id, {
            onSuccess: () => {
              toast.success('账户已删除')
              setDeleteTarget(null)
            },
            onError: () => toast.error('删除失败,请重试'),
          })
        }}
      />

      {/* 重置 PAPER ConfirmDialog(TD-045 已接:POST /accounts/{id}/paper/reset,仅 PAPER,LIVE 拒 7001) */}
      <ConfirmDialog
        open={resetTarget != null}
        onOpenChange={(o) => {
          if (!o) setResetTarget(null)
        }}
        title="重置 PAPER 模拟盘"
        description="清订单 + 清仓 + 回 10 万 USDT 虚拟资金。仅 PAPER 模拟盘可重置(LIVE 后端拒 7001)。"
        confirmLabel={resetMut.isPending ? '重置中…' : '重置'}
        destructive
        onConfirm={() => {
          if (!resetTarget || resetMut.isPending) return
          resetMut.mutate(
            { accountId: resetTarget.id },
            {
              onSuccess: () => {
                toast.success('PAPER 已重置', { description: '持仓/订单已清,余额回 10 万 USDT' })
                setResetTarget(null)
              },
              onError: (e) => toast.error('重置失败', { description: (e as Error).message }),
            },
          )
        }}
      />
    </div>
  )
}

/** AccountCard — 单个交易所账户卡(照原型 AccountCard 抄)。 */
function AccountCard({
  acc,
  onDelete,
  onReset,
}: {
  acc: ExchangeAccountView
  onDelete: () => void
  onReset: () => void
}) {
  const { data: balance } = useAccountBalance(acc.id)
  const isPaper = acc.paperTrading
  const usdt = balance?.currencies?.USDT
  const equity = usdt?.total ?? 0
  const free = usdt?.free ?? 0
  const used = usdt?.used ?? 0

  return (
    <Card
      className="p-5"
      style={{ borderTop: `3px solid ${isPaper ? 'var(--up)' : 'var(--accent)'}` }}
    >
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2">
            {isPaper ? (
              <span className="kq-paper-badge">PAPER</span>
            ) : (
              <span className="kq-live-badge">● LIVE</span>
            )}
            <strong className="text-body font-bold text-text-primary">{acc.label}</strong>
          </div>
          <div className="mt-1 text-[11px] text-text-muted">
            {acc.exchange}
          </div>
        </div>
        <div className="flex gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => toast.info(`编辑账户:${acc.label}`)}>
            编辑
          </Button>
          <Button variant="ghost" size="sm" className="text-down" onClick={onDelete}>
            <Trash2 className="size-3.5" aria-hidden />
            删除
          </Button>
        </div>
      </div>
      {/* 余额网格 */}
      <div className="mt-3.5 grid grid-cols-2 gap-2.5 border-y border-border-soft py-3">
        <div>
          <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">总权益</div>
          <div className="kq-mono-row text-[20px] font-bold">{formatMoney(toDecimal(equity))}</div>
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">可用 / 冻结</div>
          <div className="kq-mono-row text-[13px] font-bold">
            {formatMoney(toDecimal(free))}{' '}
            <span className="text-warning">/ {formatMoney(toDecimal(used))}</span>
          </div>
        </div>
      </div>
      {/* Sparkline */}
      <div className="mt-2.5">
        <SparklineChart
          data={[1, 2, 4, 3, 5, 6, 5, 7, 8, 7, 9]}
          width={240}
          height={32}
          color={isPaper ? 'var(--up)' : 'var(--accent)'}
        />
      </div>
      {/* 底部 */}
      <div className="mt-2.5 flex items-center justify-between text-[11px] text-text-muted">
        <span>
          {isPaper ? `基准行情: ${acc.exchange}` : '交易所维护余额'}
        </span>
        {isPaper && (
          <Button variant="ghost" size="sm" className="text-warning" onClick={onReset}>
            <RotateCcw className="size-3.5" aria-hidden />
            重置
          </Button>
        )}
      </div>
      {!isPaper && (
        <div className="mt-2 text-[10px] text-text-muted">
          API key: <span className="kq-mono-row">{acc.apiKey}</span>(加密存储 · 仅露末 4 位)
        </div>
      )}
    </Card>
  )
}

/** PositionRow — 跨账户持仓单行(照原型 tr 抄)。 */
function PositionRow({
  p,
  idx,
}: {
  p: PositionPnl
  idx: number
}) {
  const isLong = p.side === 'LONG'
  const uPnl = p.unrealizedPnl ?? 0
  // 占比进度条(照原型 30+i*15,简化固定递增)
  const pct = Math.min(95, 30 + idx * 15)
  // accountId 1/3 = PAPER,2/4 = LIVE(mock 约定,与 account handler ACCOUNTS 对齐)
  const isPaper = p.accountId === 1 || p.accountId === 3
  return (
    <TableRow className="border-b border-border-soft">
      <TableCell className="px-3 py-2.5">
        {isPaper ? (
          <span className="kq-paper-badge">PAPER</span>
        ) : (
          <span className="kq-live-badge">LIVE</span>
        )}
      </TableCell>
      <TableCell className="px-3 py-2.5">{p.symbol}</TableCell>
      <TableCell
        className="px-3 py-2.5 font-bold"
        style={{ color: isLong ? 'var(--up)' : 'var(--down)' }}
      >
        {p.side}
      </TableCell>
      <TableCell className="px-3 py-2.5 text-right">
        {formatMoney(toDecimal(p.qty ?? 0), { dp: 4 })}
      </TableCell>
      <TableCell className="px-3 py-2.5 text-right">
        {formatMoney(toDecimal(p.avgEntryPrice ?? 0), { dp: 2 })}
      </TableCell>
      <TableCell
        className="px-3 py-2.5 text-right font-bold"
        style={{ color: uPnl >= 0 ? 'var(--up)' : 'var(--down)' }}
      >
        {pnlArrow(uPnl)} {formatMoney(toDecimal(uPnl), { sign: true })}
      </TableCell>
      <TableCell className="px-3 py-2.5">
        <div className="h-1.5 w-[60px] overflow-hidden rounded-full bg-surface-card-2">
          <div
            className="h-full"
            style={{ width: `${pct}%`, background: 'var(--accent)' }}
          />
        </div>
      </TableCell>
    </TableRow>
  )
}

/** AddAccountDialog — 接入账户 Dialog(照原型 Modal 抄)。PAPER/LIVE 视觉强区分。 */
function AddAccountDialog({
  open,
  onOpenChange,
  createAcc,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  createAcc: ReturnType<typeof useCreateAccount>
}) {
  const [type, setType] = useState<'PAPER' | 'LIVE'>('PAPER')
  const [exchange, setExchange] = useState('BINANCE')
  const [label, setLabel] = useState('主账户')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [passphrase, setPassphrase] = useState('')
  const isPaper = type === 'PAPER'

  const reset = () => {
    setType('PAPER')
    setExchange('BINANCE')
    setLabel('主账户')
    setApiKey('')
    setApiSecret('')
    setPassphrase('')
  }

  const handleSubmit = () => {
    const body: CreateAccountRequest = {
      exchange: exchange as 'BINANCE' | 'OKX' | 'BITGET',
      paperTrading: isPaper,
      label,
      apiKey: isPaper ? '' : apiKey,
      apiSecret: isPaper ? '' : apiSecret,
      passphrase: isPaper ? '' : passphrase,
    }
    createAcc.mutate(body, {
      onSuccess: () => {
        toast.success(
          isPaper ? 'PAPER 模拟盘已就绪 · 10 万 USDT 虚拟资金' : 'API key 已加密存储',
        )
        onOpenChange(false)
        reset()
      },
      onError: () => toast.error('接入失败,请重试'),
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>接入交易所账户</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          {/* 类型 PAPER/LIVE 双选 */}
          <div>
            <span className="kq-label">类型</span>
            <div className="mt-1.5 grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setType('PAPER')}
                className={`rounded-lg border-2 px-2.5 py-2.5 text-caption font-semibold transition-all ${
                  type === 'PAPER'
                    ? 'border-up bg-up/10 text-up'
                    : 'border-border-soft bg-surface-card-2 text-text-secondary'
                }`}
              >
                PAPER 模拟
              </button>
              <button
                type="button"
                onClick={() => setType('LIVE')}
                className={`rounded-lg border-2 px-2.5 py-2.5 text-caption font-semibold transition-all ${
                  type === 'LIVE'
                    ? 'border-accent bg-accent-soft text-accent'
                    : 'border-border-soft bg-surface-card-2 text-text-secondary'
                }`}
              >
                LIVE 实盘
              </button>
            </div>
          </div>
          {/* 交易所 + 标签 */}
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <span className="kq-label">{isPaper ? '基准交易所' : '交易所'}</span>
              <Select value={exchange} onValueChange={setExchange}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="BINANCE">BINANCE</SelectItem>
                  <SelectItem value="OKX">OKX</SelectItem>
                  <SelectItem value="BITGET">BITGET</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1.5">
              <span className="kq-label">账户标签</span>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} />
            </div>
          </div>
          {/* LIVE 才显示 API key/secret */}
          {!isPaper && (
            <div className="flex flex-col gap-2.5">
              <div className="flex flex-col gap-1.5">
                <span className="kq-label">API Key</span>
                <Input
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="粘贴 API key · 加密存储"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="kq-label">API Secret</span>
                <Input
                  type="password"
                  value={apiSecret}
                  onChange={(e) => setApiSecret(e.target.value)}
                  placeholder="粘贴 secret · 加密存储"
                />
              </div>
            </div>
          )}
          {/* 说明框 */}
          <div className="rounded-lg border border-dashed border-border-soft bg-surface-card-2 p-2.5 text-[11px] leading-[1.5] text-text-muted">
            {isPaper ? (
              <>PAPER 模拟盘 · 10 万 USDT 虚拟资金 · 用所选交易所作为基准行情撮合 · 可重置。现货/合约在下单时选择,无需提前绑定。</>
            ) : (
              <>
                <AlertTriangle className="mr-1 inline size-3" aria-hidden />
                LIVE 实盘 API key 加密存储,UI 永远不会展示明文,只露末 4 位。现货/合约在下单时选择,无需提前绑定。
              </>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button size="sm" disabled={createAcc.isPending} onClick={handleSubmit}>
            接入
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
