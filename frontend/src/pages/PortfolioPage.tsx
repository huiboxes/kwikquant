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
import { Chip } from '@/components/Chip'
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
 * Portfolio=资产盘点,紧凑表头总可用 + 资产 4 Stat + 账户卡 + 现货持有表 + 策略持仓表。
 *
 * 资金分层(不折叠,显式列出):
 *  - 现金(USDT):顶部"可用资金"主指标 + AccountCard USDT 详情(总权益/可用/冻结)
 *  - 现货持有(非 USDT):独立表跨账户聚合 summary.accounts[].balances 非 USDT,显式列出(不折叠),
 *    不折算估值(守"平台 USDT 本位,不假装管理用户没授权卖出资产"口径);AccountCard 显前 3 + 查看全部链接
 *  - 策略持仓(PositionPnl):独立表,改名"策略持仓(合约)"(主动头寸 vs 现货被动库存,语义分层)
 *
 * hover 修复(A 独立容器,反"白底卡+内部 hover 灰+padding 堆叠"模式):
 *  - 持仓表/现货持有表删 Card 白底包,改 surface-card-2 次级面独立容器(rounded-lg + border-soft)
 *  - table.tsx 表头不 hover(TableRow hover 限定 tbody);行 hover 用 surface-3(比 surface-hover 深)
 *  - TableCell padding 收紧 px-4 py-2(减灰带宽度)
 *
 * 总资产口径"可用资金(USDT)"= summary.accounts USDT total 之和(不含非 USDT 估值)。
 * typography 字重不改(用户决定;DESIGN.md L268/L372 字重 400 契约漂移待补齐)。
 */
type PositionPnl = components['schemas']['PositionPnl']
type AccountSummary = components['schemas']['AccountSummary']

export function PortfolioPage() {
  const navigate = useNavigate()

  const { data: userAccounts, isLoading, error, refetch } = useAccounts()
  const { data: summary } = usePortfolioSummary()
  const { data: pnl } = usePortfolioPnl()

  const accounts = summary?.accounts ?? []
  const usdtOf = (a: AccountSummary) => a.balances?.find((b) => b.currency === 'USDT')
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
  const paperIds = new Set((userAccounts ?? []).filter((a) => a.paperTrading).map((a) => a.id))

  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
  }

  return (
    <div className="flex flex-col gap-lg">
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
            <Stat label="可用 USDT" value={formatMoney(totalFree)} mono sub="未冻结" />
            <Stat label="冻结 USDT" value={formatMoney(totalUsed)} mono sub="挂单占用" />
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

      {/* 现货持有(非 USDT)—— 跨账户聚合,显式列出,不折叠,不折算估值 */}
      <SpotHoldingsTable accounts={accounts} />

      {/* 策略持仓(合约)—— PositionPnl 主动头寸,独立 surface-card-2 容器(反白底卡+hover灰模式) */}
      <div>
        <SectionTitle
          title="策略持仓(合约)"
          sub="实时推送 · 持仓数量/均价/盈亏变化"
          right={
            <Button variant="ghost" size="sm" onClick={() => navigate('/trade')}>
              管理交易
              <ArrowRight className="size-4" aria-hidden />
            </Button>
          }
        />
        <div className="overflow-hidden rounded-lg border border-border-soft bg-surface-card-2">
          <div className="overflow-auto">
            <Table>
              <TableHeader>
                <TableRow className="text-left text-[10px] uppercase tracking-[0.04em] text-text-muted">
                  <TableHead className="px-4 py-2">账户</TableHead>
                  <TableHead className="px-4 py-2">Symbol</TableHead>
                  <TableHead className="px-4 py-2">方向</TableHead>
                  <TableHead className="px-4 py-2 text-right">数量</TableHead>
                  <TableHead className="px-4 py-2 text-right">均价</TableHead>
                  <TableHead className="px-4 py-2 text-right">未实现</TableHead>
                  <TableHead className="px-4 py-2">占比</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody className="kq-mono-row">
                {positions.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} className="p-6">
                      <EmptyState title="无持仓" description="当前无策略持仓" />
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
        </div>
      </div>
    </div>
  )
}

/** SpotHoldingsTable — 现货持有(非 USDT)跨账户聚合表。显式列出,不折叠,不折算估值。 */
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
    <div>
      <SectionTitle
        title="现货持有(非 USDT)"
        sub="跨账户聚合 · 不折算估值"
        right={<Chip label={`共 ${count} 种`} color="accent" />}
      />
      <div className="overflow-hidden rounded-lg border border-border-soft bg-surface-card-2">
        <div className="max-h-[400px] overflow-auto">
          <Table>
            <TableHeader>
              <TableRow className="text-left text-[10px] uppercase tracking-[0.04em] text-text-muted">
                <TableHead className="px-4 py-2">账户</TableHead>
                <TableHead className="px-4 py-2">币种</TableHead>
                <TableHead className="px-4 py-2 text-right">可用</TableHead>
                <TableHead className="px-4 py-2 text-right">冻结</TableHead>
                <TableHead className="px-4 py-2 text-right">总</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className="kq-mono-row">
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="p-6">
                    <EmptyState title="无现货持有" description="当前账户无非 USDT 资产" />
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((r, i) => (
                  <TableRow key={i} className="border-b border-border-soft">
                    <TableCell className="px-4 py-2">{r.account}</TableCell>
                    <TableCell className="px-4 py-2">{r.currency}</TableCell>
                    <TableCell className="px-4 py-2 text-right">
                      {formatMoney(toDecimal(r.free), { dp: 4 })}
                    </TableCell>
                    <TableCell className="px-4 py-2 text-right text-warning">
                      {formatMoney(toDecimal(r.used), { dp: 4 })}
                    </TableCell>
                    <TableCell className="px-4 py-2 text-right font-bold">
                      {formatMoney(toDecimal(r.total), { dp: 4 })}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  )
}

/** PositionRow — 策略持仓单行。占比 = 持仓 notional / 可用资金;isPaper 按 accountId 查 accounts。 */
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
  const isPaper = p.accountId != null && paperIds.has(p.accountId)
  const markPrice = p.currentPrice ?? p.avgEntryPrice ?? 0
  const notional = toDecimal(p.qty ?? 0).times(toDecimal(markPrice))
  const pct = totalEquity.gt(0)
    ? Math.min(100, notional.div(totalEquity).times(100).toNumber())
    : 0
  return (
    <TableRow className="border-b border-border-soft">
      <TableCell className="px-4 py-2">
        {isPaper ? (
          <span className="kq-paper-badge">模拟</span>
        ) : (
          <span className="kq-live-badge">实盘</span>
        )}
      </TableCell>
      <TableCell className="px-4 py-2">{p.symbol}</TableCell>
      <TableCell
        className="px-4 py-2 font-bold"
        style={{ color: isLong ? 'var(--up)' : 'var(--down)' }}
      >
        {p.side}
      </TableCell>
      <TableCell className="px-4 py-2 text-right">
        {formatMoney(toDecimal(p.qty ?? 0), { dp: 4 })}
      </TableCell>
      <TableCell className="px-4 py-2 text-right">
        {formatMoney(toDecimal(p.avgEntryPrice ?? 0), { dp: 2 })}
      </TableCell>
      <TableCell
        className="px-4 py-2 text-right font-bold"
        style={{ color: uPnl >= 0 ? 'var(--up)' : 'var(--down)' }}
      >
        {pnlArrow(uPnl)} {formatMoney(toDecimal(uPnl), { sign: true })}
      </TableCell>
      <TableCell className="px-4 py-2">
        <div className="h-1.5 w-[80px] overflow-hidden rounded-full bg-surface-card">
          <div
            className="h-full"
            style={{ width: `${pct}%`, background: 'var(--accent)' }}
          />
        </div>
      </TableCell>
    </TableRow>
  )
}
