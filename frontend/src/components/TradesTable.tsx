import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Chip } from '@/components/Chip'
import { formatMoney } from '@/lib/backtestFormat'
import type { components } from '@/types/api-gen'

type TradeRecordDto = components['schemas']['TradeRecordDto']

/**
 * TradesTable — 回测交易明细(spec §5 step 22)。
 *
 * TradeRecordDto 字段:id / time(ISO) / side(buy|sell) / price / amount / fee。
 * shadcn Table + DataRowMono 风格(IBM Plex Mono + tnum,数字列右对齐)。
 * side 用 Chip 涨跌色(buy=up, sell=down)。
 * time ISO → "YYYY-MM-DD HH:MM:SS" 展示(去 T/Z)。
 *
 * 金额红线:price/amount/fee 走 formatMoney(内部 Decimal,不 Number()/parseFloat)。
 */
export interface TradesTableProps {
  trades: TradeRecordDto[]
}

function formatTradeTime(iso: string): string {
  // ISO UTC → "YYYY-MM-DD HH:MM:SS UTC"(显式 UTC 标记,避免用户误读为本地时间)
  return new Date(iso).toISOString().replace('T', ' ').replace(/\.\d{3}Z$/, ' UTC')
}

export function TradesTable({ trades }: TradesTableProps) {
  return (
    <div className="rounded-xl bg-surface-card p-lg shadow-card">
      <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Trades</p>
      <h3 className="mt-sm font-display text-h3">交易明细</h3>
      <div className="mt-lg">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>时间</TableHead>
              <TableHead>方向</TableHead>
              <TableHead className="text-right">价格</TableHead>
              <TableHead className="text-right">数量</TableHead>
              <TableHead className="text-right">手续费</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {trades.map((t) => (
              <TableRow key={t.id}>
                <TableCell className="font-mono-num text-mono tabular-nums text-text-secondary">
                  {formatTradeTime(t.time)}
                </TableCell>
                <TableCell>
                  <Chip
                    label={t.side === 'buy' ? '买' : '卖'}
                    color={t.side === 'buy' ? 'up' : 'down'}
                    size="sm"
                  />
                </TableCell>
                <TableCell className="text-right font-mono-num text-mono tabular-nums">
                  {formatMoney(t.price)}
                </TableCell>
                <TableCell className="text-right font-mono-num text-mono tabular-nums">
                  {formatMoney(t.amount)}
                </TableCell>
                <TableCell className="text-right font-mono-num text-mono tabular-nums">
                  {formatMoney(t.fee)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}
