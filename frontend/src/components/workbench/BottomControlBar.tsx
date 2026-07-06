import { useState } from 'react'
import { format } from 'date-fns'
import { ChevronRight, Calendar as CalendarIcon } from 'lucide-react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'
import { Calendar } from '@/components/ui/calendar'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/components/ui/alert-dialog'
import type { DateRange } from 'react-day-picker'

const SYMBOLS = ['BTC/USDT', 'ETH/USDT', 'SOL/USDT']
const INTERVALS = ['1m', '5m', '15m', '1h', '4h', '1d']

interface Props {
  strategyId: number
  isPublished: boolean
  onRunBacktest: (params: {
    symbol: string
    interval: string
    range: DateRange | undefined
  }) => void
  onRunLive: (params: { symbol: string; interval: string }) => void
  isSubmitting: boolean
}

/**
 * BottomControlBar — 编辑器底部控制栏(spec §4.3 选择器组件)。
 *
 * 交易对/interval Select + 日期 Popover+Calendar 双月选 + Backtest/Run Live Button。
 * AlertDialog 替 window.confirm(实盘/回测前确认)。
 */
export function BottomControlBar({
  isPublished,
  onRunBacktest,
  onRunLive,
  isSubmitting,
}: Props) {
  const [symbol, setSymbol] = useState('BTC/USDT')
  const [interval, setIntervalState] = useState('1h')
  const [range, setRange] = useState<DateRange | undefined>()
  const [confirmKind, setConfirmKind] = useState<'backtest' | 'live' | null>(null)

  const rangeLabel = range?.from
    ? `${format(range.from, 'yyyy-MM-dd')} → ${range.to ? format(range.to, 'yyyy-MM-dd') : format(range.from, 'yyyy-MM-dd')}`
    : '选择日期范围'

  return (
    <div className="flex items-center gap-md border-t border-border bg-surface-card px-lg py-md">
      <Select value={symbol} onValueChange={setSymbol}>
        <SelectTrigger className="w-[140px] rounded-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {SYMBOLS.map((s) => (
            <SelectItem key={s} value={s}>
              {s}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select value={interval} onValueChange={setIntervalState}>
        <SelectTrigger className="w-[72px] rounded-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {INTERVALS.map((i) => (
            <SelectItem key={i} value={i}>
              {i}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Popover>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            className="rounded-full font-mono text-body-sm"
          >
            <CalendarIcon className="h-[14px] w-[14px]" />
            {rangeLabel}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="range"
            selected={range}
            onSelect={setRange}
            numberOfMonths={2}
          />
        </PopoverContent>
      </Popover>
      <div className="flex-1" />
      <Button
        variant="outline"
        disabled={isSubmitting}
        onClick={() => setConfirmKind('backtest')}
      >
        {isSubmitting && confirmKind === 'backtest' ? '提交中…' : 'Backtest'}
      </Button>
      <Button
        variant="default"
        disabled={!isPublished || isSubmitting}
        onClick={() => setConfirmKind('live')}
      >
        Run Live <ChevronRight className="ml-xs h-[14px] w-[14px]" />
      </Button>

      <AlertDialog
        open={confirmKind !== null}
        onOpenChange={(o) => !o && setConfirmKind(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {confirmKind === 'backtest' ? '跑回测?' : 'Run Live?'}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {confirmKind === 'backtest'
                ? '用默认参数跑回测?\n\n初始资金 10000 USDT\nBTC/USDT · BINANCE · 1h\n最近 30 天'
                : '用实盘跑这个策略?将提交真实订单。\n\nBTC/USDT · BINANCE · 1h'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (confirmKind === 'backtest')
                  onRunBacktest({ symbol, interval, range })
                else onRunLive({ symbol, interval })
                setConfirmKind(null)
              }}
            >
              确认
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
