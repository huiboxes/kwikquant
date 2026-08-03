import {
  AlertTriangle,
  Check,
  ChevronsUpDown,
  Pause,
  Play,
  Plus,
  Search,
  StopCircle,
  Trash2,
  Upload,
} from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { StrategyStatusBadge } from '@/components/StrategyStatusBadge'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import type { StrategyDetailDto } from '@/api/strategy'
import { stripContractSuffix } from '@/lib/symbol'
import { cn } from '@/lib/utils'

interface StrategySelectorProps {
  strategies: StrategyDetailDto[]
  selectedId: number | null
  onSelect: (id: number) => void
  selected: StrategyDetailDto | null
  draftCodeId: number | null
  onCreate: () => void
  onPublish: () => void
  onStart: () => void
  onPause: () => void
  onStop: () => void
  onDelete: () => void
  onFsm: () => void
}

/**
 * StrategySelector — 策略工作台 sub-header bar。
 * 替代旧的水平卡片 rail：左侧下拉选策略 + 中间状态信息 + 右侧操作按钮组。
 */
export function StrategySelector({
  strategies,
  selectedId,
  onSelect,
  selected,
  draftCodeId,
  onCreate,
  onPublish,
  onStart,
  onPause,
  onStop,
  onDelete,
  onFsm,
}: StrategySelectorProps) {
  const status = selected?.status
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const filtered = strategies.filter((s) =>
    (s.name + ' ' + stripContractSuffix(s.symbol)).toLowerCase().includes(query.trim().toLowerCase()),
  )

  return (
    <div className="flex flex-wrap items-center gap-xs border-b border-border-soft bg-surface-card px-lg py-sm">
      {/* 策略下拉选择器(可搜 Combobox,照 SymbolSelect 范式 port) */}
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger className="flex h-9 w-[240px] items-center justify-between rounded-md border border-border-soft bg-surface-card px-sm text-body-sm hover:bg-surface-hover">
          <span className={cn('text-text-primary', !selected && 'text-text-muted')}>
            {selected
              ? `${selected.name} · ${stripContractSuffix(selected.symbol)}`
              : '选择策略'}
          </span>
          <ChevronsUpDown className="size-4 text-text-muted" aria-hidden />
        </PopoverTrigger>
        <PopoverContent className="w-[280px] p-0" align="start">
          <Command shouldFilter={false}>
            <div className="flex items-center border-b border-border-soft px-sm">
              <Search className="size-4 text-text-muted" aria-hidden />
              <CommandInput
                placeholder="搜索策略…"
                className="h-9"
                value={query}
                onValueChange={setQuery}
              />
            </div>
            <CommandList>
              <CommandEmpty>无匹配策略</CommandEmpty>
              <CommandGroup>
                {filtered.map((s) => (
                  <CommandItem
                    key={s.id}
                    value={String(s.id)}
                    onSelect={() => {
                      onSelect(s.id)
                      setOpen(false)
                    }}
                    className="flex items-center justify-between"
                  >
                    <span className="flex items-center gap-xxs">
                      <span className="text-text-primary">{s.name}</span>
                      <span className="kq-mono-row text-text-muted">
                        · {stripContractSuffix(s.symbol)}
                      </span>
                    </span>
                    {s.id === selectedId && <Check className="size-3 text-accent" aria-hidden />}
                  </CommandItem>
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>

      {/* 新建策略 */}
      <Button variant="ghost" size="icon-sm" onClick={onCreate} title="新建策略">
        <Plus aria-hidden />
      </Button>

      {/* 当前策略信息:状态 badge 可点击弹流转规则(strategy 状态,跟 code 状态分离) */}
      {selected && (
        <>
          <button
            type="button"
            onClick={onFsm}
            className="transition-opacity hover:opacity-70"
            title="查看状态流转规则"
          >
            <StrategyStatusBadge status={selected.status.toLowerCase()} />
          </button>
          <span className="text-caption text-text-muted">
            {stripContractSuffix(selected.symbol)} · {selected.exchange} · {selected.intervalValue}
          </span>
        </>
      )}

      <div className="flex-1" />

      {/* 操作按钮组 */}
      <Button
        variant="ghost"
        size="sm"
        className="gap-xs text-text-secondary"
        onClick={onPublish}
        disabled={!draftCodeId}
      >
        <Upload aria-hidden />
        发布版本
      </Button>

      {/* 状态相关按钮 */}
      {status === 'DRAFT' && (
        <Button
          variant="ghost"
          size="sm"
          className="gap-xs"
          onClick={() =>
            toast.warning('需要先发布代码', { description: '草稿策略无法直接启动' })
          }
        >
          <Play aria-hidden /> 启动
        </Button>
      )}
      {status === 'RUNNING' && (
        <Button variant="ghost" size="sm" className="gap-xs text-text-secondary" onClick={onPause}>
          <Pause aria-hidden /> 暂停
        </Button>
      )}
      {(status === 'PAUSED' || status === 'READY') && (
        <Button size="sm" className="gap-xs" onClick={onStart}>
          <Play aria-hidden /> 启动
        </Button>
      )}
      {status === 'ERROR' && (
        <Button
          variant="ghost"
          size="sm"
          className="gap-xs"
          onClick={onStart}
        >
          <AlertTriangle aria-hidden /> 重试
        </Button>
      )}
      {status === 'STOPPED' && (
        <Button size="sm" className="gap-xs" onClick={onStart}>
          <Play aria-hidden /> 重新启动
        </Button>
      )}

      {/* 停止按钮(RUNNING/PAUSED/ERROR 时显示) */}
      {(status === 'RUNNING' || status === 'PAUSED' || status === 'ERROR') && (
        <Button
          variant="ghost"
          size="sm"
          className="gap-xs text-down hover:text-down"
          onClick={onStop}
        >
          <StopCircle aria-hidden /> 停止
        </Button>
      )}

      {/* 删除策略(破坏性,父组件 ConfirmDialog 二次确认) */}
      <Button
        variant="ghost"
        size="icon-sm"
        className="text-down hover:text-down"
        onClick={onDelete}
        title="删除策略"
      >
        <Trash2 aria-hidden />
      </Button>
    </div>
  )
}
