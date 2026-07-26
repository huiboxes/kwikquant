import { useNavigate } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'
import type { Decimal } from 'decimal.js'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
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
import { AccountCard } from '@/components/AccountCard'
import { useAccounts } from '@/hooks/useAccounts'
import { usePortfolioSummary, usePortfolioPnl } from '@/hooks/usePortfolio'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * PortfolioPage — 资产盘点工作台(只读)。账户管理(添加/删除/重置)归 Settings 交易账户 tab。
 *
 * IA(memory + 设计师 subagent):Dashboard=引导中枢(Hero+旅程+运行策略+动态+权益曲线);
 * Portfolio=资产盘点,紧凑表头总可用 + 资产 4 Stat + 账户卡 + 跨账户持仓。两页平级非层级,
 * 总数字同语义(可用资金 USDT)异形态(Dashboard Hero 引导锚 / Portfolio 表头盘点锚)异用途。
 *
 * 删 EquityCurve(与 Dashboard 同端点 usePortfolioEquityCurve 冗余,权益曲线属运行表现归 Dashboard)。
 * 总资产口径改"可用资金(USDT)"= summary.accounts USDT total 之和(平台 USDT 本位,不折算非 USDT
 * 估值,不假装管理用户没授权卖出的资产)。非 USDT 资产折叠在 AccountCard 内。Stat 换资产维度
 * (账户数/持仓数/可用 free/冻结 used),删已实现/手续费(让 Dashboard PerformanceCard)。
 */
type PositionPnl = components['schemas']['PositionPnl']

export function PortfolioPage() {
  const navigate = useNavigate()

  const { data: userAccounts, isLoading, error, refetch } = useAccounts()
  const { data: summary } = usePortfolioSummary()
  const { data: pnl } = usePortfolioPnl()

  const accounts = summary?.accounts ?? []
  const usdtOf = (a: (typeof accounts)[number]) =>
    a.balances?.find((b) => b.currency === 'USDT')
  // 金额红线:聚合用 decimal.js .plus(),不用 JS +
  const totalEquity = accounts.reduce(
    (sum, a) => sum.plus(toDecimal(usdtOf(a)?.total ?? 0)),
    toDecimal(0),
  )
  const totalFree = accounts.reduce(
    (sum, a) => sum.plus(toDecimal(usdtOf(a)?.free ?? 0)),
    toDecimal(0),
  )
  const totalUsed = accounts.reduce(
    (sum, a) => sum.plus(toDecimal(usdtOf(a)?.used ?? 0)),
    toDecimal(0),
  )
  const totalPnl = pnl?.totalUnrealizedPnl ?? 0
  const positions = pnl?.positions ?? []
  const paperCount = (userAccounts ?? []).filter((a) => a.paperTrading).length
  const liveCount = (userAccounts ?? []).length - paperCount
  // PositionRow 用:按 accountId 查 paperTrading(PositionPnl 无 paperTrading 字段)
  const paperIds = new Set((userAccounts ?? []).filter((a) => a.paperTrading).map((a) => a.id))

  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div>
        <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">组合总览</h1>
        <p className="mt-1.5 text-body-sm text-text-secondary">
          多账户聚合 · 部分账户拉取失败会降级展示
        </p>
      </div>

      {/* 紧凑盘点表头(总可用锚 + 资产 4 Stat) */}
      <Card className="p-5">
        <div className="flex flex-wrap items-end justify-between gap-5">
          <div>
            <div className="text-caption font-semibold uppercase tracking-[0.05em] text-text-muted">
              可用资金(USDT)
            </div>
            <div className="kq-mono-row mt-1 text-[36px] font-bold tracking-[-0.02em]">
              $ {formatMoney(totalEquity)}
            </div>
            <div className={`kq-mono-row mt-1 text-caption font-semibold ${pnlTextClass(totalPnl)}`}>
              {pnlArrow(totalPnl)} {formatMoney(toDecimal(totalPnl), { sign: true })} 当日未实现
            </div>
          </div>
          <div className="grid grid-cols-4 gap-5 max-[760px]:grid-cols-2">
            <Stat
              label="账户数"
              value={String((userAccounts ?? []).length)}
              mono
              sub={`${paperCount} 模拟 · ${liveCount} 实盘`}
            />
            <Stat label="持仓数" value={String(positions.length)} mono sub="多账户聚合" />
            <Stat
              label="可用 USDT"
              value={formatMoney(totalFree)}
              mono
              sub="未冻结"
            />
            <Stat
              label="冻结 USDT"
              value={formatMoney(totalUsed)}
              mono
              sub="挂单占用"
            />
          </div>
        </div>
      </Card>

      {/* Account cards */}
      <div>
        <SectionTitle title="交易所账户" sub="多账户余额明细" />
        <div className="grid grid-cols-3 gap-3.5 max-[1100px]:grid-cols-2 max-[680px]:grid-cols-1">
          {isLoading ? (
            <Card className="col-span-3 p-6">
              <LoadingState rows={3} />
            </Card>
          ) : (
            (userAccounts ?? []).map((a) => <AccountCard key={a.id} acc={a} />)
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
                  <PositionRow key={i} p={p} paperIds={paperIds} totalEquity={totalEquity} />
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </Card>
    </div>
  )
}

/** PositionRow — 跨账户持仓单行。占比 = 持仓 notional / 可用资金;isPaper 按 accountId 查 accounts。 */
function PositionRow({
  p,
  paperIds,
  totalEquity,
}: {
  p: PositionPnl
  paperIds: Set<number>
  totalEquity: Decimal
}) {
  const isLong = p.side === 'LONG'
  const uPnl = p.unrealizedPnl ?? 0
  // isPaper 按 accountId 查 accounts.paperTrading(PositionPnl 无 paperTrading 字段)
  const isPaper = p.accountId != null && paperIds.has(p.accountId)
  // 占比 = 持仓 notional / 可用资金(qty × currentPrice,null 用 avgEntryPrice);可用资金 0 → 0%,clamp 100 防溢出
  const markPrice = p.currentPrice ?? p.avgEntryPrice ?? 0
  const notional = toDecimal(p.qty ?? 0).times(toDecimal(markPrice))
  const pct = totalEquity.gt(0)
    ? Math.min(100, notional.div(totalEquity).times(100).toNumber())
    : 0
  return (
    <TableRow className="border-b border-border-soft">
      <TableCell className="px-3 py-2.5">
        {isPaper ? (
          <span className="kq-paper-badge">模拟</span>
        ) : (
          <span className="kq-live-badge">实盘</span>
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
