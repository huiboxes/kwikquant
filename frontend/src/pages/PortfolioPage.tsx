import { useNavigate } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'
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
import { EquityCurveChart } from '@/components/charts/EquityCurveChart'
import { AccountCard } from '@/components/AccountCard'
import { useAccounts } from '@/hooks/useAccounts'
import {
  usePortfolioSummary,
  usePortfolioPnl,
  usePortfolioEquityCurve,
} from '@/hooks/usePortfolio'
import { toDecimal, formatMoney } from '@/lib/money'
import { pnlArrow, pnlTextClass } from '@/lib/pnl'
import type { components } from '@/types/api-gen'

/**
 * PortfolioPage — 组合总览(只读)。账户管理(添加/删除/重置)归 Settings 交易账户 tab,
 * TopBar 账户 chip 一跳即达。本页纯看总览,不动手。
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
 *
 * 金额:equity/balance/frozen/uPnl/qty/avgEntryPrice 全 toDecimal + formatMoney,展示全 kq-mono-row。
 * 涨跌(LONG/SHORT/uPnl)用 pnlArrow + pnlTextClass(a11y 箭头+色,不靠色单独表达)。
 * 图标 lucide-react(ArrowRight),不用 emoji。
 * PAPER/LIVE 强区分:AccountCard border-top 色 + badge(只读态,无管理操作)。
 * 文案:Stat sub 中文 `X 模拟 · Y 实盘`,PositionRow badge 中文,不泄露 PAPER/LIVE 枚举。
 */
type PositionPnl = components['schemas']['PositionPnl']
type EquityPointDto = components['schemas']['EquityPointDto']

export function PortfolioPage() {
  const navigate = useNavigate()

  const { data: accounts, isLoading, error, refetch } = useAccounts()
  const { data: summary } = usePortfolioSummary()
  const { data: pnl } = usePortfolioPnl()
  const { data: equityCurve } = usePortfolioEquityCurve()

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
      <div>
        <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">组合总览</h1>
        <p className="mt-1.5 text-body-sm text-text-secondary">
          多账户聚合 · 部分账户拉取失败会降级展示
        </p>
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
              sub={`${paperCount} 模拟 · ${liveCount} 实盘`}
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
          sub="API key 加密存储 · 仅露末 4 位"
        />
        <div className="grid grid-cols-3 gap-3.5 max-[1100px]:grid-cols-2 max-[680px]:grid-cols-1">
          {isLoading ? (
            <Card className="col-span-3 p-6">
              <LoadingState rows={3} />
            </Card>
          ) : (
            (accounts ?? []).map((a) => (
              <AccountCard key={a.id} acc={a} />
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
    </div>
  )
}

/** AccountCard 已抽到 `@/components/AccountCard`(managed/readonly 双态共享)。 */

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

