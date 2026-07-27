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
  /** 已提交的值( popover 外 trigger 显示这个;打开时 sync 到 internal 作为选区起点) */
  value: DateRange | undefined
  /** 提交回调:仅在用户点"确定"或"清空"时触发(选区间中间态不触发) */
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
  /** PopoverContent align,默认 'start' */
  align?: 'start' | 'center' | 'end'
}

/**
 * DateRangePicker — 日期区间选择器(内部 local state + 确定提交)。
 *
 * 核心设计(参考 shadcn 官方 date-range-picker):选区中间态不触发外部 onChange,
 * 避免调用方(如 HistoryPage)在用户选区间途中就 setState → query loading →
 * 页面 reflow/Popover 异常 → "没法选区间"。
 *
 * 交互流程:
 * 1. 点 trigger 打开 Popover → handleOpenChange 同步 value → internal(干净起点)
 * 2. Calendar onSelect 只更新 internal(resetOnSelect=true,点新日期重置为 from)
 * 3. 预设按钮 → setInternal({from,to}) (不立即提交,用户可继续改)
 * 4. 点"确定" → onChange(internal) + 关闭 (唯一提交路径,disabled 当 internal 不完整)
 * 5. 点"清空" → setInternal(undefined) + onChange(undefined)
 *
 * trigger 显示 value(提交态,稳定);popover 内底部 status 显示 internal(当前选区)。
 *
 * resetOnSelect=true 修复 react-day-picker v10 默认 falsy 致"起始时间还是一年前"
 * BUG(完整 range 时点新日期 addToRange 走 isAfter 分支只换 to 不换 from)。
 *
 * 样式对齐 DESIGN.md token:PopoverContent 用 rounded-xl/border-border-soft/
 * bg-surface-card/shadow-pop,Calendar 包 bg-transparent 让 surface-card 透出。
 */
export function DateRangePicker({
  value,
  onChange,
  presets = DEFAULT_PRESETS,
  disabledFuture = true,
  numberOfMonths = 2,
  className,
  trigger,
  align = 'start',
}: DateRangePickerProps) {
  // internal 选区(popover 内的临时选区,不触发外部 onChange)
  const [internal, setInternal] = useState<DateRange | undefined>(value)
  const [open, setOpen] = useState(false)

  // 打开 popover 时同步 value → internal(每次打开从已提交值开始,丢弃上次未提交的选区)
  const handleOpenChange = (next: boolean) => {
    if (next) setInternal(value)
    setOpen(next)
  }

  // trigger 显示提交态(value);popover 内底部显示当前选区(internal)
  const submitLabel = value?.from && value?.to
    ? `${formatDate(value.from.toISOString())} → ${formatDate(value.to.toISOString())}`
    : '选择日期'
  const internalReady = !!internal?.from && !!internal?.to
  const internalLabel = internalReady
    ? `${formatDate(internal!.from!.toISOString())} → ${formatDate(internal!.to!.toISOString())}`
    : '请选择起止日期'

  const handlePreset = (days: number) => {
    const to = new Date()
    const from = new Date()
    from.setDate(from.getDate() - days)
    setInternal({ from, to })
  }

  const handleConfirm = () => {
    if (!internalReady) return
    onChange(internal)
    setOpen(false)
  }

  const handleClear = () => {
    setInternal(undefined)
    onChange(undefined)
  }

  const defaultTrigger = (
    <button
      type="button"
      className="flex h-[36px] cursor-pointer items-center gap-xxs rounded-pill bg-surface-3 px-sm transition-colors hover:bg-surface-hover"
    >
      <CalendarDays className="size-4 text-text-muted" aria-hidden />
      <span className="kq-mono-row text-body-sm font-semibold text-text-primary">
        {submitLabel}
      </span>
      <ChevronDown className="size-3.5 text-text-muted" aria-hidden />
    </button>
  )

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
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
        {/* 预设区间快捷按钮 → 只更新 internal(不立即提交) */}
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
        {/* Calendar 用 internal(选区),onSelect 只更新 internal 不触发外部 onChange */}
        <Calendar
          mode="range"
          numberOfMonths={numberOfMonths}
          selected={internal}
          onSelect={setInternal}
          resetOnSelect
          disabled={disabledFuture ? { after: new Date() } : undefined}
          className="bg-transparent"
        />
        <div className="flex items-center justify-between gap-sm border-t border-border-soft p-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleClear}
            className="text-text-muted"
          >
            <X className="size-3.5" aria-hidden />
            清空
          </Button>
          <span className="kq-mono-row text-caption text-text-muted">
            {internalLabel}
          </span>
          <Button
            variant="ghost"
            size="sm"
            onClick={handleConfirm}
            disabled={!internalReady}
          >
            确定
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
