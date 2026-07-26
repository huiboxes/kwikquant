import { useState, type ReactNode } from 'react'
import { CalendarDays, ChevronDown, X } from 'lucide-react'
import type { DateRange } from 'react-day-picker'
import { Calendar } from '@/components/ui/calendar'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { formatDate } from '@/lib/format'
import { cn } from '@/lib/utils'

export interface DateRangePreset {
  label: string
  days: number
}

const DEFAULT_PRESETS: DateRangePreset[] = [
  { label: '近 1 月', days: 30 },
  { label: '近 3 月', days: 90 },
  { label: '近 6 月', days: 180 },
  { label: '近 1 年', days: 365 },
]

export interface DateRangePickerProps {
  /** 受控值(undefined → 显示"选择日期") */
  value: DateRange | undefined
  onChange: (r: DateRange | undefined) => void
  /** 预设按钮,默认近 1 月/3 月/6 月/1 年 */
  presets?: DateRangePreset[]
  /** 禁用未来日期,默认 true */
  disabledFuture?: boolean
  /** 双月视图(避免翻页),默认 2 */
  numberOfMonths?: 1 | 2
  /** PopoverContent 附加 className */
  className?: string
  /** 自定义触发器(不传用默认 Pill trigger;传则 asChild 注入 ref/aria) */
  trigger?: ReactNode
  /** 受控 open(可选,不传内部自管) */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** PopoverContent align,默认 'start' */
  align?: 'start' | 'center' | 'end'
}

/**
 * DateRangePicker — 日期区间选择器(Popover + Calendar range)。
 *
 * 抽自 BottomControlBar 内联实现,复用到 HistoryPage,统一策略页/历史页的日期 UI。
 *
 * 交互核心:硬编码 `resetOnSelect={true}` 修复 react-day-picker v10 默认 falsy 导致
 * 的 BUG —— 默认 from=一年前 + 完整 range 时点新日期,addToRange 走 isAfter(date,from)
 * 分支只换 to 不换 from → "起始时间还是一年前"。resetOnSelect=true 后点新日期重置为
 * {from:新日期, to:undefined},符合用户预期"直接选开始再选结束,不需翻页取消旧选"。
 *
 * 双月视图(numberOfMonths=2)避免选 1 年区间翻页 11 次。清空按钮让用户能重置选择。
 *
 * 触发器灵活:不传 trigger 用默认 Pill(图标+rangeLabel+chevron);传则 PopoverTrigger
 * asChild 注入(历史页用 Input 形态)。
 *
 * 样式对齐 DESIGN.md token:PopoverContent 用 rounded-xl/border-border-soft/
 * bg-surface-card/shadow-pop,Calendar
 * 包 bg-transparent 让 surface-card 透出,避免 shadcn 原生 bg-background 纯白割裂。
 */
export function DateRangePicker({
  value,
  onChange,
  presets = DEFAULT_PRESETS,
  disabledFuture = true,
  numberOfMonths = 2,
  className,
  trigger,
  open: controlledOpen,
  onOpenChange,
  align = 'start',
}: DateRangePickerProps) {
  const [internalOpen, setInternalOpen] = useState(false)
  const open = controlledOpen ?? internalOpen
  const setOpen = onOpenChange ?? setInternalOpen

  const rangeReady = !!value?.from && !!value?.to
  const rangeLabel = value?.from && value?.to
    ? `${formatDate(value.from.toISOString())} → ${formatDate(value.to.toISOString())}`
    : '选择日期'

  const handlePreset = (days: number) => {
    const to = new Date()
    const from = new Date()
    from.setDate(from.getDate() - days)
    onChange({ from, to })
  }

  const handleClear = () => onChange(undefined)

  const defaultTrigger = (
    <button
      type="button"
      className="flex h-[36px] cursor-pointer items-center gap-xxs rounded-pill bg-surface-3 px-sm transition-colors hover:bg-surface-hover"
    >
      <CalendarDays className="size-4 text-text-muted" aria-hidden />
      <span className="kq-mono-row text-body-sm font-semibold text-text-primary">
        {rangeLabel}
      </span>
      <ChevronDown className="size-3.5 text-text-muted" aria-hidden />
    </button>
  )

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        {trigger ?? defaultTrigger}
      </PopoverTrigger>
      <PopoverContent
        align={align}
        className={cn(
          'w-auto p-0 rounded-xl border border-border-soft bg-surface-card shadow-pop',
          className,
        )}
      >
        {/* 预设区间快捷按钮(免手挑,直击"时间选择不正常"痛点) */}
        <div className="flex flex-wrap gap-xxs border-b border-border-soft p-2">
          {presets.map((p) => (
            <button
              key={p.label}
              type="button"
              onClick={() => handlePreset(p.days)}
              className="rounded-pill bg-surface-3 px-sm py-xxs text-caption text-text-secondary transition-colors hover:bg-surface-hover hover:text-text-primary"
            >
              {p.label}
            </button>
          ))}
        </div>
        <Calendar
          mode="range"
          numberOfMonths={numberOfMonths}
          selected={value}
          onSelect={onChange}
          resetOnSelect
          disabled={disabledFuture ? { after: new Date() } : undefined}
          className="bg-transparent"
        />
        <div className="flex items-center justify-between gap-sm border-t border-border-soft p-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleClear}
            disabled={!rangeReady}
            className="text-text-muted"
          >
            <X className="size-3.5" aria-hidden />
            清空
          </Button>
          <span className="kq-mono-row text-caption text-text-muted">
            {rangeReady ? rangeLabel : '请选择起止日期'}
          </span>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setOpen(false)}
            disabled={!rangeReady}
          >
            确定
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
