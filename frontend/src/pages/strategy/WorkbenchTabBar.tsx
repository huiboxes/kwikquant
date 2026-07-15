import { Plus, X } from 'lucide-react'
import type { StrategyCodeDto } from '@/api/strategy'

interface WorkbenchTabBarProps {
  codes: StrategyCodeDto[] | undefined
  activeCodeId: number | null
  onTabChange: (codeId: number) => void
  onNewDraft: () => void
  onDiscardDraft: (codeId: number) => void
}

/** 代码状态 → tab 状态点颜色 */
function statusDotColor(status: string): string {
  if (status === 'DRAFT') return 'bg-up'
  if (status === 'PUBLISHED') return 'bg-info'
  return 'bg-text-muted'
}

/**
 * WorkbenchTabBar — 代码版本 tab 栏(照原型 workbench.html TabBar)。
 * Active tab 有底部 accent 指示条。
 *
 * × 关闭按钮只对 DRAFT 草稿显示(放弃当前草稿);PUBLISHED/ARCHIVED 是历史版本,不可删。
 * onDiscardDraft(codeId) 由父层调 DELETE /codes/{codeId}(仅 DRAFT 可删,非 DRAFT 返 409)。
 */
export function WorkbenchTabBar({
  codes,
  activeCodeId,
  onTabChange,
  onNewDraft,
  onDiscardDraft,
}: WorkbenchTabBarProps) {
  const items = codes ?? []

  return (
    <div className="flex items-end gap-xxs bg-surface-card-2 px-xs pt-1">
      {items.length === 0 ? (
        <div className="px-sm py-xxs text-caption text-text-muted">暂无代码</div>
      ) : (
        items.map((c) => {
          const isActive = c.id === activeCodeId
          const isDraft = c.status === 'DRAFT'
          return (
            <button
              key={c.id}
              type="button"
              onClick={() => onTabChange(c.id)}
              className={`relative flex items-center gap-xs rounded-t-lg px-sm py-xxs text-caption transition-colors ${
                isActive
                  ? 'bg-surface-card font-semibold text-text-primary'
                  : 'text-text-muted hover:bg-surface-card/60 hover:text-text-primary'
              }`}
            >
              {/* 状态点 */}
              <span className={`size-2 shrink-0 rounded-full ${statusDotColor(c.status)}`} />
              {/* 版本名 */}
              <span>代码 v{c.versionNumber}</span>
              {/* 关闭按钮:仅 DRAFT 可点击放弃(DELETE /codes/{codeId},仅 DRAFT 可删) */}
              {isDraft && (
                <span
                  role="button"
                  tabIndex={0}
                  className="ml-xxs flex items-center text-text-muted hover:text-down"
                  onClick={(e) => {
                    e.stopPropagation()
                    onDiscardDraft(c.id)
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.stopPropagation()
                      onDiscardDraft(c.id)
                    }
                  }}
                  title="放弃草稿"
                >
                  <X className="size-[14px]" aria-hidden />
                </span>
              )}
              {/* Active 底部指示条 */}
              {isActive && (
                <span className="absolute bottom-0 left-0 right-0 h-[2px] rounded-t-full bg-accent" />
              )}
            </button>
          )
        })
      )}

      {/* 新建草稿按钮 */}
      <button
        type="button"
        onClick={onNewDraft}
        className="mb-0.5 flex size-7 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-hover hover:text-text-primary"
        title="新建草稿"
      >
        <Plus className="size-[18px]" aria-hidden />
      </button>
    </div>
  )
}
