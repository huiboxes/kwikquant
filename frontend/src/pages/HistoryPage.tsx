import { useState } from 'react'
import { toast } from 'sonner'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Stat } from '@/components/Stat'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { useTradeHistory, useTradeHistoryStats } from '@/hooks/useTradeHistory'
import { toDecimal, formatMoney } from '@/lib/money'
import { formatDateTime } from '@/lib/format'
import type { components } from '@/types/api-gen'

/**
 * HistoryPage — 交易历史(照原型 done-design/components/HistoryPage.jsx port)。
 *
 * 适配后端契约(TradeHistoryDto):
 *  - 无每笔 pnl 字段(pnl 是统计级,见 StatsDto.realizedPnl)→ "已实现"列显示 —
 *  - 无 isPaper/accountLabel(只有 accountId number)→ 账户列显示 #id
 *  - side 后端小写(buy/sell)→ 展示转大写 + 涨跌色
 * 金额:qty/price/fee/volume 全 decimal.js(toDecimal + formatMoney),不碰 parseFloat/Number。
 * 数据走 react-query(useTradeHistory + useTradeHistoryStats),筛选/分页参数变化自动重查。
 */
type TradeHistoryDtoType = components['schemas']['TradeHistoryDto']
const PAGE_SIZE = 10

export function HistoryPage() {
  const [page, setPage] = useState(1)
  const [account, setAccount] = useState('all')
  const [symbol, setSymbol] = useState('all')
  const [startTime, setStartTime] = useState('')
  const [endTime, setEndTime] = useState('')

  const accountId = account === 'all' ? undefined : parseInt(account, 10)
  const sym = symbol === 'all' ? undefined : symbol
  const query = {
    page,
    pageSize: PAGE_SIZE,
    accountId,
    symbol: sym,
    startTime: startTime || undefined,
    endTime: endTime || undefined,
  }
  const statsQuery = {
    accountId,
    startTime: startTime || undefined,
    endTime: endTime || undefined,
  }

  const { data: pageData, isLoading, error } = useTradeHistory(query)
  const { data: stats } = useTradeHistoryStats(statsQuery)

  const trades = pageData?.content ?? []
  const total = pageData?.total ?? 0
  const totalPages = pageData?.totalPages ?? 1
  const totalVolume = stats?.totalVolume ?? 0
  const totalFees = stats?.totalFees ?? 0
  const realizedPnl = stats?.realizedPnl ?? 0

  const resetFilters = () => {
    setAccount('all')
    setSymbol('all')
    setStartTime('')
    setEndTime('')
    setPage(1)
  }

  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => setPage(1)} />
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <div>
          <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">交易历史</h1>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            订单 + 成交聚合 · 按 账户 / Symbol / 时间筛选 · CSV / JSON 导出
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => toast.info(`CSV 已导出:${total} 条记录`)}
          >
            ↓ CSV
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => toast.info(`JSON 已导出:${total} 条记录`)}
          >
            ↓ JSON
          </Button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-4 gap-3.5 max-[900px]:grid-cols-2">
        <Card className="p-5">
          <Stat label="成交笔数" value={String(total)} mono sub="筛选后" />
        </Card>
        <Card className="p-5">
          <Stat label="总成交额" value={`$ ${formatMoney(toDecimal(totalVolume))}`} mono sub="USDT" />
        </Card>
        <Card className="p-5">
          <Stat
            label="总手续费"
            value={formatMoney(toDecimal(totalFees))}
            mono
            sub="USDT"
            tone="accent"
          />
        </Card>
        <Card className="p-5">
          <Stat
            label="已实现盈亏"
            value={formatMoney(toDecimal(realizedPnl), { sign: true })}
            mono
            sub="USDT"
            tone={realizedPnl >= 0 ? 'up' : 'down'}
          />
        </Card>
      </div>

      {/* Filters */}
      <Card className="p-5">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5">
            <span className="kq-label">账户</span>
            <Select
              value={account}
              onValueChange={(v) => {
                setAccount(v)
                setPage(1)
              }}
            >
              <SelectTrigger className="w-auto">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部</SelectItem>
                <SelectItem value="1">PAPER</SelectItem>
                <SelectItem value="2">LIVE</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="kq-label">Symbol</span>
            <Select
              value={symbol}
              onValueChange={(v) => {
                setSymbol(v)
                setPage(1)
              }}
            >
              <SelectTrigger className="w-auto">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部</SelectItem>
                <SelectItem value="BTC">BTC/USDT</SelectItem>
                <SelectItem value="ETH">ETH/USDT</SelectItem>
                <SelectItem value="SOL">SOL/USDT</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="kq-label">开始日期</span>
            <Input
              type="date"
              className="w-auto"
              value={startTime}
              onChange={(e) => {
                setStartTime(e.target.value)
                setPage(1)
              }}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="kq-label">结束日期</span>
            <Input
              type="date"
              className="w-auto"
              value={endTime}
              onChange={(e) => {
                setEndTime(e.target.value)
                setPage(1)
              }}
            />
          </div>
          <Button variant="ghost" size="sm" onClick={resetFilters}>
            重置
          </Button>
          <div className="flex-1" />
          <div className="kq-mono-row text-caption text-text-muted">
            {total} 条 · {(page - 1) * PAGE_SIZE + 1}–{Math.min(page * PAGE_SIZE, total)}
          </div>
        </div>
      </Card>

      {/* Table */}
      <Card className="overflow-hidden p-0">
        <div className="overflow-auto">
          <Table>
            <TableHeader>
              <TableRow className="text-left text-caption uppercase tracking-[0.04em] text-text-muted">
                <TableHead className="py-2.5 px-3.5">时间</TableHead>
                <TableHead className="py-2.5 px-3.5">账户</TableHead>
                <TableHead className="py-2.5 px-3.5">Symbol</TableHead>
                <TableHead className="py-2.5 px-3.5">方向</TableHead>
                <TableHead className="py-2.5 px-3.5 text-right">数量</TableHead>
                <TableHead className="py-2.5 px-3.5 text-right">价格</TableHead>
                <TableHead className="py-2.5 px-3.5 text-right">手续费</TableHead>
                <TableHead className="py-2.5 px-3.5 text-right">已实现</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className="kq-mono-row">
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={8} className="p-6">
                    <LoadingState rows={5} />
                  </TableCell>
                </TableRow>
              ) : trades.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} className="p-6">
                    <EmptyState title="无匹配记录" description="调整筛选条件或更换时间范围" />
                  </TableCell>
                </TableRow>
              ) : (
                trades.map((t) => <TradeRow key={t.orderId} t={t} />)
              )}
            </TableBody>
          </Table>
        </div>
        {/* Pagination */}
        <div className="flex items-center justify-between border-t border-border-soft p-3 px-4">
          <div className="text-caption text-text-muted">
            第 {page} / {totalPages} 页 · 每页 {PAGE_SIZE} 条
          </div>
          <div className="flex gap-1.5">
            <Button
              variant="ghost"
              size="sm"
              disabled={page <= 1}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
            >
              ‹ 上一页
            </Button>
            {Array.from({ length: totalPages }).map((_, i) => (
              <Button
                key={i}
                variant={i + 1 === page ? 'default' : 'outline'}
                size="sm"
                onClick={() => setPage(i + 1)}
              >
                {i + 1}
              </Button>
            ))}
            <Button
              variant="ghost"
              size="sm"
              disabled={page >= totalPages}
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
            >
              下一页 ›
            </Button>
          </div>
        </div>
      </Card>
    </div>
  )
}

function TradeRow({ t }: { t: TradeHistoryDtoType }) {
  const side = t.side.toUpperCase()
  const isBuy = t.side === 'buy'
  const qtyDp = t.filledQty < 1 ? 4 : 2
  const priceDp = t.filledAvgPrice < 1 ? 4 : 2
  return (
    <TableRow className="border-b border-border-soft">
      <TableCell className="py-2.5 px-3.5">{formatDateTime(t.createdAt)}</TableCell>
      <TableCell className="py-2.5 px-3.5 text-text-secondary">#{t.accountId}</TableCell>
      <TableCell className="py-2.5 px-3.5">{t.symbol}</TableCell>
      <TableCell
        className="py-2.5 px-3.5 font-bold"
        style={{ color: isBuy ? 'var(--up)' : 'var(--down)' }}
      >
        {side}
      </TableCell>
      <TableCell className="py-2.5 px-3.5 text-right">
        {formatMoney(toDecimal(t.filledQty), { dp: qtyDp })}
      </TableCell>
      <TableCell className="py-2.5 px-3.5 text-right">
        {formatMoney(toDecimal(t.filledAvgPrice), { dp: priceDp })}
      </TableCell>
      <TableCell className="py-2.5 px-3.5 text-right" style={{ color: 'var(--warning)' }}>
        {formatMoney(toDecimal(t.totalFee), { dp: 4 })}
      </TableCell>
      <TableCell className="py-2.5 px-3.5 text-right text-text-muted">—</TableCell>
    </TableRow>
  )
}

// 注:TradeHistoryDto 类型直接从 api-gen 的 components.schemas 取,
