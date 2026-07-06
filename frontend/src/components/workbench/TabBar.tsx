import { Link } from 'react-router-dom'
import { X, Plus } from 'lucide-react'
import { useWorkbenchTabs } from '@/hooks/useWorkbenchTabs'
import { useStrategy } from '@/hooks/useStrategy'
import { cn } from '@/lib/utils'

/**
 * TabBar — 多策略 tab 横排。
 * 每个 tab: 圆点(橙=active/灰=非) + {name}.py + 关闭 X。
 * 末尾 + 按钮跳 /strategies 选策略。
 */
export function TabBar() {
  const { tabs, active, setActive, removeTab } = useWorkbenchTabs()
  return (
    <div className="flex items-center gap-sm border-b border-border bg-surface-card px-sm">
      {tabs.map((id) => (
        <TabItem
          key={id}
          id={id}
          isActive={id === active}
          onSelect={setActive}
          onClose={removeTab}
        />
      ))}
      <Link
        to="/strategies"
        className="inline-flex h-9 items-center justify-center rounded-md px-sm text-text-secondary hover:bg-surface-hover hover:text-text-primary"
        aria-label="新建策略"
        title="新建策略"
      >
        <Plus className="h-[16px] w-[16px]" />
      </Link>
    </div>
  )
}

function TabItem({
  id,
  isActive,
  onSelect,
  onClose,
}: {
  id: number
  isActive: boolean
  onSelect: (id: number) => void
  onClose: (id: number) => void
}) {
  const { data: strategy } = useStrategy(id)
  const name = strategy ? `${strategy.name}.py` : `strategy-${id}.py`
  return (
    <div
      className={cn(
        'group inline-flex h-9 items-center gap-sm rounded-md px-md font-mono text-body-sm cursor-pointer',
        isActive
          ? 'bg-surface-card-2 text-text-primary'
          : 'text-text-secondary hover:bg-surface-hover',
      )}
      onClick={() => onSelect(id)}
      role="tab"
      aria-selected={isActive}
    >
      <span
        className={cn(
          'h-[6px] w-[6px] rounded-full',
          isActive ? 'bg-accent' : 'bg-text-muted',
        )}
      />
      <span>{name}</span>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation()
          onClose(id)
        }}
        className="opacity-0 group-hover:opacity-100 hover:text-text-primary"
        aria-label={`关闭 ${name}`}
      >
        <X className="h-[14px] w-[14px]" />
      </button>
    </div>
  )
}
