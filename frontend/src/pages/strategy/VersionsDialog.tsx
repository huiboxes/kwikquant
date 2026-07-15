import { GitBranch } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/Chip'
import { formatDateTime } from '@/lib/format'
import type { StrategyCodeDto } from '@/api/strategy'

/**
 * VersionsDialog — 代码版本列表对话框。
 *
 * 从 StrategyPage 提取:倒序版本列表 + "发布新版本"按钮(关闭本弹窗并打开 PublishDialog)。
 * VersionRow 为内部子组件,不导出。
 */

interface VersionsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  codes: StrategyCodeDto[] | undefined
  strategyName: string | undefined
  onPublishNew: () => void
}

/** 版本行(DRAFT/PUBLISHED/ARCHIVED 三态样式)。 */
function VersionRow({ c }: { c: StrategyCodeDto }) {
  const isDraft = c.status === 'DRAFT'
  const isPublished = c.status === 'PUBLISHED'
  return (
    <div
      className={`flex items-center gap-2.5 rounded-md border p-3 ${
        isDraft ? 'border-accent bg-accent-soft' : 'border-transparent bg-surface-card-2'
      }`}
    >
      {/* 状态圆点 */}
      <span
        className={`size-2.5 shrink-0 rounded-full border-2 ${
          isDraft
            ? 'border-accent'
            : isPublished
              ? 'border-up'
              : 'border-text-muted'
        }`}
      />
      {/* 版本号 + chip + changelog */}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-caption font-semibold text-text-primary">
            v{c.versionNumber}
          </span>
          {isDraft && <Chip color="accent" label="DRAFT" />}
          {isPublished && <Chip color="up" label="PUBLISHED" />}
          {!isDraft && !isPublished && <Chip label="ARCHIVED" />}
        </div>
        <div className="mt-0.5 text-[11px] text-text-muted">{c.changelog}</div>
      </div>
      {/* 更新时间 */}
      <div className="text-[10px] text-text-muted">
        {formatDateTime(c.updatedAt)}
      </div>
    </div>
  )
}

export function VersionsDialog(props: VersionsDialogProps) {
  const { open, onOpenChange, codes, strategyName, onPublishNew } = props

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[560px]">
        <DialogHeader>
          <DialogTitle>代码版本</DialogTitle>
          <DialogDescription>当前策略 · {strategyName ?? '…'}</DialogDescription>
        </DialogHeader>

        {/* 统计 + 图例 */}
        <div className="mb-3 flex items-center justify-between">
          <div className="text-caption text-text-secondary">
            倒序展示 · 共 {codes?.length ?? 0} 个版本
          </div>
          <Chip color="info" label="3 态:草稿 / 已发布 / 已归档" />
        </div>

        {/* 版本列表 */}
        <div className="flex flex-col gap-2">
          {(codes ?? []).map((c) => (
            <VersionRow key={c.id} c={c} />
          ))}
          {(!codes || codes.length === 0) && (
            <div className="py-4 text-center text-text-muted">暂无代码版本</div>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            关闭
          </Button>
          <Button onClick={onPublishNew}>
            <GitBranch className="size-3.5" aria-hidden /> 发布新版本
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
