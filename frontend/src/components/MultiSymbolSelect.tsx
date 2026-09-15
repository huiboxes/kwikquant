import { useState } from 'react'
import { Check, ChevronsUpDown, Search, X } from 'lucide-react'
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

/** 与后端 validatePortfolioSymbols 同口径(2-20);细粒度校验(canonical/去重)由后端单源裁决。 */
export const PORTFOLIO_MIN_SYMBOLS = 2
export const PORTFOLIO_MAX_SYMBOLS = 20

interface MultiSymbolSelectProps {
  values: string[]
  onChange: (symbols: string[]) => void
  exchange: string
  marketType: string
  className?: string
}

/**
 * MultiSymbolSelect — 组合回测多标的选择器(SymbolSelect 的多选对偶)。
 *
 * 同一 Popover + Command 视觉语言(pill trigger、搜索、24h 成交额、mono symbol);
 * 差异:点击 toggle 选中态(**不关闭浮层**,连续多选),Check 标记已选,达上限 20 后
 * 未选项禁点。footer 提示数量窗口与 on_bars 入口约束(组合回测策略契约,
 * docs/strategy-api.md §1)。选中态走中性色(DESIGN.md:选中不用品牌橙)。
 */
export function MultiSymbolSelect({
  values,
  onChange,
  exchange,
  marketType,
  className,
}: MultiSymbolSelectProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const { data: symbols = [], isLoading } = useTradableSymbols(exchange, marketType)
  const filtered = symbols.filter((s) =>
    stripContractSuffix(s.symbol).toLowerCase().includes(query.trim().toLowerCase()),
  )
  const atCap = values.length >= PORTFOLIO_MAX_SYMBOLS
  const label =
    values.length === 0
      ? '选标的(组合)'
      : values.length === 1
        ? values[0]
        : `${values[0]} +${values.length - 1}`

  const toggle = (sym: string) => {
    if (values.includes(sym)) {
      onChange(values.filter((v) => v !== sym))
    } else if (!atCap) {
      onChange([...values, sym])
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        data-testid="multi-symbol-trigger"
        className={cn(
          'flex h-[36px] max-w-[240px] cursor-pointer items-center gap-xxs rounded-pill border-0 bg-surface-3 px-sm hover:bg-surface-hover',
          className,
        )}
      >
        <span className="truncate text-body-sm font-semibold text-text-primary">{label}</span>
        <ChevronsUpDown className="size-3 shrink-0 text-text-muted" aria-hidden />
      </PopoverTrigger>
      <PopoverContent className="w-[300px] p-0" align="start">
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
                const selected = values.includes(sym)
                const blocked = !selected && atCap
                return (
                  <CommandItem
                    key={s.symbol}
                    value={s.symbol}
                    disabled={blocked}
                    onSelect={() => toggle(sym)}
                    className={cn('flex items-center justify-between', blocked && 'opacity-50')}
                    title={blocked ? `最多 ${PORTFOLIO_MAX_SYMBOLS} 个标的` : undefined}
                  >
                    <span className="font-mono tnum text-text-primary">{sym}</span>
                    <span className="flex items-center gap-xxs">
                      <span className="font-mono tnum text-caption text-text-muted">
                        {formatMoneyCN(toDecimal(s.quoteVolume))}
                      </span>
                      {selected && <Check className="size-3 text-text-primary" aria-hidden />}
                    </span>
                  </CommandItem>
                )
              })}
            </CommandGroup>
          </CommandList>
          <div className="flex items-center justify-between border-t border-border-soft px-sm py-xs">
            <span className="text-caption text-text-muted">
              已选 {values.length} / {PORTFOLIO_MIN_SYMBOLS}–{PORTFOLIO_MAX_SYMBOLS} · 策略须定义
              on_bars(ctx)
            </span>
            {values.length > 0 && (
              <button
                type="button"
                onClick={() => onChange([])}
                data-testid="multi-symbol-clear"
                className="flex items-center gap-[2px] rounded-pill px-xs py-[2px] text-caption font-medium text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary"
              >
                <X className="size-3" aria-hidden />
                清空
              </button>
            )}
          </div>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
