import { useState } from 'react'
import { Check, ChevronsUpDown, Search } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import { useTradableSymbols } from '@/hooks/useMarket'
import { stripContractSuffix } from '@/lib/symbol'
import { toDecimal, formatMoneyCN } from '@/lib/money'
import { cn } from '@/lib/utils'

interface SymbolSelectProps {
  value: string
  onChange: (symbol: string) => void
  exchange: string
  marketType: string
  trigger?: 'dialog' | 'pill'
  icon?: LucideIcon
  className?: string
}

/**
 * SymbolSelect — 策略页标的下拉(创建对话框 + 底部控制栏两处复用)。
 *
 * Combobox 模式(Popover + Command):支持搜索(标的可能上百)，每项显标的符号(左)+
 * 24h 成交额(右，formatMoneyCN 中文紧凑)，按后端 quoteVolume desc 排序展示。
 * 对外 value/onChange 一律 strip 后干净 symbol(兜底后端透传 :USDT)。
 * trigger 形态:dialog=宽 trigger(创建对话框);pill=紧凑 trigger(底部控制栏，带图标)。
 */
export function SymbolSelect({
  value,
  onChange,
  exchange,
  marketType,
  trigger = 'dialog',
  icon: Icon,
  className,
}: SymbolSelectProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const { data: symbols = [], isLoading } = useTradableSymbols(exchange, marketType)
  const display = stripContractSuffix(value)
  const filtered = symbols.filter((s) =>
    stripContractSuffix(s.symbol).toLowerCase().includes(query.trim().toLowerCase()),
  )

  const triggerEl =
    trigger === 'pill' ? (
      <PopoverTrigger
        className={cn(
          'flex h-[36px] cursor-pointer items-center gap-xxs rounded-pill border-0 bg-surface-3 px-sm hover:bg-surface-hover',
          className,
        )}
      >
        {Icon && <Icon className="size-4 text-text-muted" aria-hidden />}
        <span className="text-body-sm font-semibold text-text-primary">{display || '选标的'}</span>
        <ChevronsUpDown className="size-3 text-text-muted" aria-hidden />
      </PopoverTrigger>
    ) : (
      <PopoverTrigger
        className={cn(
          'flex h-9 w-full items-center justify-between rounded-md border border-border-soft bg-surface-card px-sm text-body-sm hover:bg-surface-hover',
          className,
        )}
      >
        <span className={cn('text-text-primary', !display && 'text-text-muted')}>
          {display || '选标的'}
        </span>
        <ChevronsUpDown className="size-4 text-text-muted" aria-hidden />
      </PopoverTrigger>
    )

  return (
    <Popover open={open} onOpenChange={setOpen}>
      {triggerEl}
      <PopoverContent className="w-[280px] p-0" align="start">
        <Command shouldFilter={false}>
          <div className="flex items-center border-b border-border-soft px-sm">
            <Search className="size-4 text-text-muted" aria-hidden />
            <CommandInput
              placeholder="搜索标的…"
              className="h-9"
              value={query}
              onValueChange={setQuery}
            />
          </div>
          <CommandList>
            <CommandEmpty>{isLoading ? '加载中…' : '无匹配标的'}</CommandEmpty>
            <CommandGroup>
              {filtered.map((s) => {
                const sym = stripContractSuffix(s.symbol)
                const selected = sym === display
                return (
                  <CommandItem
                    key={s.symbol}
                    value={s.symbol}
                    onSelect={() => {
                      onChange(sym)
                      setOpen(false)
                    }}
                    className="flex items-center justify-between"
                  >
                    <span className="font-mono tnum text-text-primary">{sym}</span>
                    <span className="flex items-center gap-xxs">
                      <span className="font-mono tnum text-caption text-text-muted">
                        {formatMoneyCN(toDecimal(s.quoteVolume))}
                      </span>
                      {selected && <Check className="size-3 text-accent" aria-hidden />}
                    </span>
                  </CommandItem>
                )
              })}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
