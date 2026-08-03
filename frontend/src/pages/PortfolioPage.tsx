import { useMemo, useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { ArrowRight, ChevronDown, ChevronUp } from 'lucide-react'
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
  EmptyRow,
} from '@/components/ui/table'
import { SectionTitle } from '@/components/SectionTitle'
import { Stat } from '@/components/Stat'
import { Chip } from '@/components/Chip'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { AccountCard } from '@/components/AccountCard'
import { useAccounts } from '@/hooks/useAccounts'
import { useUiStore } from '@/stores/uiStore'
import { usePortfolioSummary, usePortfolioPnl } from '@/hooks/usePortfolio'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import { cn } from '@/lib/utils'
import type { components } from '@/types/api-gen'

/**
 * PortfolioPage — 资产盘点工作台(只读)。
 *
 * 资金分层(用户拍板,推翻折叠):
 *  - 现金(USDT):顶部"可用资金"主指标 + AccountCard USDT 详情
 *  - 现货持有(非 USDT):独立表跨账户聚合 summary.accounts 非 USDT,显式列出(不折叠),不折算估值
 *  - 跨账户持仓(PositionPnl):独立表"跨账户持仓"(后端 getPnl 不按 SPOT/PERP 过滤,两类都返)
 *
 * 持仓表/现货表样式照搬 TradingPage PositionsTable(用户指认可):Card p-5 白底 +
 * SectionTitle 在内 + Table + TableCell px-3 py-2.5 + TableHead text-caption。
 * 不用 surface-card-2 灰底(用户反馈"大量灰丑")。AccountCard 模拟盘也回白底(靠 badge 区分)。
 */
type PositionPnl = components['schemas']['PositionPnl']
type AccountSummary = components['schemas']['AccountSummary']

export function PortfolioPage() {
  const navigate = useNavigate()
  const tradeMode = useUiStore((s) => s.tradeMode)
  const { data: userAccounts, isLoading, error, refetch } = useAccounts()
  const { data: summary } = usePortfolioSummary(tradeMode)
  const { data: pnl } = usePortfolioPnl(tradeMode)

  const accounts = summary?.accounts ?? []
  const usdtOf = (a: AccountSummary) => a.balances?.find((b) => b.currency === 'USDT')
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
  const positions = useMemo(() => pnl?.positions ?? [], [pnl])
  // 列排序(照 MarketPage 范式,前端 sort 避免 re-fetch)。default=后端顺序;未实现/占比 3 态 desc→asc→default。
  const [sort, setSort] = useState<'default' | 'unrealizedPnl' | 'pct'>('default')
  const [order, setOrder] = useState<'asc' | 'desc'>('desc')
  const sortedPositions = useMemo(() => {
    if (sort === 'default') return positions
    const copy = [...positions]
    copy.sort((a, b) => {
      const av = sort === 'pct'
        ? toDecimal(a.qty ?? 0).times(toDecimal(a.currentPrice ?? a.avgEntryPrice ?? 0)).div(totalEquity)
        : toDecimal(a.unrealizedPnl ?? 0)
      const bv = sort === 'pct'
        ? toDecimal(b.qty ?? 0).times(toDecimal(b.currentPrice ?? b.avgEntryPrice ?? 0)).div(totalEquity)
        : toDecimal(b.unrealizedPnl ?? 0)
      return order === 'desc' ? bv.minus(av).toNumber() : av.minus(bv).toNumber()
    })
    return copy
    // eslint-disable-next-line react-hooks/preserve-manual-memoization -- sortedPositions useMemo 必要(避免每次 render 重排);React Compiler 跳过 PortfolioPage 优化可接受(组件小),totalEquity 是 reduce 返 Decimal immutable 只读不 mutate
  }, [positions, sort, order, totalEquity])
  function toggleSort(col: 'unrealizedPnl' | 'pct') {
    if (col === sort) {
      if (order === 'desc') setOrder('asc')
      else { setSort('default'); setOrder('desc') }
    } else {
      setSort(col); setOrder('desc')
    }
  }
  const paperCount = (userAccounts ?? []).filter((a) => a.paperTrading).length
  const liveCount = (userAccounts ?? []).length - paperCount
  const paperIds = new Set((userAccounts ?? []).filter((a) => a.paperTrading).map((a) => a.id))

  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
  }

  return (
    <div className="flex flex-col gap-lg">
      <div>
        <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">组合总览</h1>
        <p className="mt-1.5 text-body-sm text-text-secondary">
          多账户汇总 · 部分账户暂时无法读取时仅展示可用账户
        </p>
      </div>

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
            <Stat label="账户数" value={String((userAccounts ?? []).length)} mono sub={`${paperCount} 模拟 · ${liveCount} 实盘`} />
            <Stat label="持仓数" value={String(positions.length)} mono sub="多账户聚合" />
            <Stat label="可用 USDT" value={formatMoney(totalFree)} mono sub="未冻结" />
            <Stat label="冻结 USDT" value={formatMoney(totalUsed)} mono sub="挂单占用" />
          </div>
        </div>
      </Card>

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

      <SpotHoldingsTable accounts={accounts} />

      <Card className="p-5">
        <SectionTitle
          title="跨账户持仓"
          sub="实时更新 · 持仓数量/均价/盈亏变化"
          right={
            <Button variant="ghost" size="sm" onClick={() => navigate('/trade')}>
              管理交易
              <ArrowRight className="size-4" aria-hidden />
            </Button>
          }
        />
        <div className="overflow-auto">
          <Table>
            <TableHeader>
              <TableRow className="text-left text-caption uppercase tracking-[0.04em] text-text-muted">
                <TableHead className="px-3 py-2">账户</TableHead>
                <TableHead className="px-3 py-2">标的</TableHead>
                <TableHead className="px-3 py-2">方向</TableHead>
                <TableHead className="px-3 py-2 text-right">数量</TableHead>
                <TableHead className="px-3 py-2 text-right">均价</TableHead>
                <TableHead className="px-3 py-2 text-right">
                  <button
                    type="button"
                    onClick={() => toggleSort('unrealizedPnl')}
                    aria-sort={sort === 'unrealizedPnl' ? (order === 'desc' ? 'descending' : 'ascending') : 'none'}
                    className="inline-flex items-center gap-xxs px-0 py-0 font-inherit text-inherit"
                  >
                    未实现
                    <SortArrows active={sort === 'unrealizedPnl'} order={order} />
                  </button>
                </TableHead>
                <TableHead className="px-3 py-2">
                  <button
                    type="button"
                    onClick={() => toggleSort('pct')}
                    aria-sort={sort === 'pct' ? (order === 'desc' ? 'descending' : 'ascending') : 'none'}
                    className="inline-flex items-center gap-xxs px-0 py-0 font-inherit text-inherit"
                  >
                    占比
                    <SortArrows active={sort === 'pct'} order={order} />
                  </button>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className="kq-mono-row">
              {positions.length === 0 ? (
                <EmptyRow colSpan={7}>
                  <EmptyState
                    title="无持仓"
                    description="当前无跨账户持仓"
                    action={
                      <Button asChild variant="ghost" size="sm">
                        <Link to="/trade">去交易页开仓</Link>
                      </Button>
                    }
                  />
                </EmptyRow>
              ) : (
                sortedPositions.map((p, i) => (
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

/** SpotHoldingsTable — 现货持有(非 USDT)跨账户聚合表。照搬 TradingPage PositionsTable 样式(Card p-5 白底)。 */
function SpotHoldingsTable({ accounts }: { accounts: AccountSummary[] }) {
  const rows = accounts.flatMap((a) =>
    (a.balances ?? [])
      .filter((b) => b.currency !== 'USDT')
      .map((b) => ({
        account: a.label ?? '',
        currency: b.currency ?? '',
        free: b.free ?? 0,
        used: b.used ?? 0,
        total: b.total ?? 0,
      })),
  )
  const count = new Set(rows.map((r) => r.currency)).size
  return (
    <Card className="p-5">
      <SectionTitle
        title="现货持有(非 USDT)"
        sub="各币种明细"
        right={<Chip label={`共 ${count} 种`} color="accent" />}
      />
      <div className="max-h-[400px] overflow-auto">
        <Table>
          <TableHeader>
            <TableRow className="text-left text-caption uppercase tracking-[0.04em] text-text-muted">
              <TableHead className="px-3 py-2">账户</TableHead>
              <TableHead className="px-3 py-2">币种</TableHead>
              <TableHead className="px-3 py-2 text-right">可用</TableHead>
              <TableHead className="px-3 py-2 text-right">冻结</TableHead>
              <TableHead className="px-3 py-2 text-right">总</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="kq-mono-row">
            {rows.length === 0 ? (
              <EmptyRow colSpan={5}>
                <EmptyState title="无现货持有" description="暂无其他币种持仓" />
              </EmptyRow>
            ) : (
              rows.map((r, i) => (
                <TableRow key={i}>
                  <TableCell className="px-3 py-2.5">{r.account}</TableCell>
                  <TableCell className="px-3 py-2.5">{r.currency}</TableCell>
                  <TableCell className="px-3 py-2.5 text-right">
                    {formatMoney(toDecimal(r.free), { dp: 4 })}
                  </TableCell>
                  <TableCell className="px-3 py-2.5 text-right text-warning">
                    {formatMoney(toDecimal(r.used), { dp: 4 })}
                  </TableCell>
                  <TableCell className="px-3 py-2.5 text-right font-bold">
                    {formatMoney(toDecimal(r.total), { dp: 4 })}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </Card>
  )
}

/** PositionRow — 跨账户持仓单行。照搬 TradingPage PositionsTable 样式。 */
function PositionRow({
  p,
  paperIds,
  totalEquity,
}: {
  p: PositionPnl
  paperIds: Set<number>
  totalEquity: Decimal
}) {
  const isLong = p.side?.toLowerCase() === 'long'
  const uPnl = p.unrealizedPnl ?? 0
  const isPaper = p.accountId != null && paperIds.has(p.accountId)
  const markPrice = p.currentPrice ?? p.avgEntryPrice ?? 0
  const notional = toDecimal(p.qty ?? 0).times(toDecimal(markPrice))
  const pct = totalEquity.gt(0)
    ? Math.min(100, notional.div(totalEquity).times(100).toNumber())
    : 0
  return (
    <TableRow>
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
        {isLong ? '做多' : '做空'}
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
        <div className="flex items-center gap-xs">
          <div className="h-1.5 w-[60px] overflow-hidden rounded-full bg-surface-card-2">
            <div
              className="h-full"
              style={{ width: `${pct}%`, background: 'var(--accent)' }}
            />
          </div>
          <span className="kq-mono-row text-caption text-text-muted">{pct.toFixed(2)}%</span>
        </div>
      </TableCell>
    </TableRow>
  )
}

/** SortArrows — 列头排序方向箭头(照 MarketPage:163-176 抄,局部不抽共享)。 */
function SortArrows({ active, order }: { active: boolean; order: 'asc' | 'desc' }) {
  return (
    <span className="flex flex-col" aria-hidden>
      <ChevronUp className={cn('size-2.5', active && order === 'asc' ? 'text-text-primary' : 'text-text-muted/40')} />
      <ChevronDown className={cn('size-2.5', active && order === 'desc' ? 'text-text-primary' : 'text-text-muted/40')} />
    </span>
  )
}
