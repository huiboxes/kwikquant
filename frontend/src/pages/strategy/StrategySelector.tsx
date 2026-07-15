import {
  AlertTriangle,
  GitBranch,
  Pause,
  Play,
  Plus,
  Square,
  Trash2,
} from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { StrategyStatusBadge } from '@/components/StrategyStatusBadge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { StrategyDetailDto } from '@/api/strategy'

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

  return (
    <div className="flex flex-wrap items-center gap-sm border-b border-border-soft bg-surface-card px-lg py-sm">
      {/* 策略下拉选择器 */}
      <Select
        value={selectedId != null ? String(selectedId) : ''}
        // eslint-disable-next-line no-restricted-syntax -- strategy id 非金额,parseInt 安全
        onValueChange={(v) => onSelect(Number(v))}
      >
        <SelectTrigger size="sm" className="w-[240px]">
          <SelectValue placeholder="选择策略" />
        </SelectTrigger>
        <SelectContent>
          {strategies.map((s) => (
            <SelectItem key={s.id} value={String(s.id)}>
              {s.name} · {s.symbol}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      {/* 新建策略 */}
      <Button variant="ghost" size="icon-sm" onClick={onCreate} title="新建策略">
        <Plus className="size-3.5" aria-hidden />
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
            {selected.symbol} · {selected.exchange} · {selected.intervalValue}
          </span>
        </>
      )}

      <div className="flex-1" />

      {/* 操作按钮组 */}
      <Button
        variant="ghost"
        size="sm"
        onClick={onPublish}
        disabled={!draftCodeId}
      >
        <GitBranch className="size-3.5" aria-hidden />
        发布版本
      </Button>

      {/* 状态相关按钮 */}
      {status === 'DRAFT' && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() =>
            toast.warning('需要先发布代码', { description: '草稿策略无法直接启动' })
          }
        >
          <Play className="size-3.5" aria-hidden /> 启动
        </Button>
      )}
      {status === 'RUNNING' && (
        <Button variant="ghost" size="sm" onClick={onPause}>
          <Pause className="size-3.5" aria-hidden /> 暂停
        </Button>
      )}
      {(status === 'PAUSED' || status === 'READY') && (
        <Button size="sm" onClick={onStart}>
          <Play className="size-3.5" aria-hidden /> 启动
        </Button>
      )}
      {status === 'ERROR' && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() =>
            toast.warning('策略异常', { description: 'Worker 运行出错,请检查日志' })
          }
        >
          <AlertTriangle className="size-3.5" aria-hidden /> 异常
        </Button>
      )}
      {status === 'STOPPED' && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() =>
            toast.warning('已停止', { description: '需重新编辑回草稿' })
          }
        >
          已停止
        </Button>
      )}

      {/* 停止按钮(RUNNING/PAUSED/ERROR 时显示) */}
      {(status === 'RUNNING' || status === 'PAUSED' || status === 'ERROR') && (
        <Button
          variant="ghost"
          size="sm"
          className="text-down hover:text-down"
          onClick={onStop}
        >
          <Square className="size-3.5" aria-hidden /> 停止
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
        <Trash2 className="size-3.5" aria-hidden />
      </Button>
    </div>
  )
}
