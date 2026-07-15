import { useState } from 'react'
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
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

/**
 * PublishDialog — 发布代码版本对话框。
 *
 * 从 StrategyPage 提取:版本号输入 + 变更说明 + 冻结警告 + 发布按钮。
 * 表单状态由父组件管理(publishing flag 控制 disabled)。
 */

interface PublishDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  latestVersion: number | null
  publishing: boolean
  onPublish: (version: string, changelog: string) => void
}

export function PublishDialog(props: PublishDialogProps) {
  const { open, onOpenChange, latestVersion, publishing, onPublish } = props

  // 本地表单状态
  const [version, setVersion] = useState('')
  const [changelog, setChangelog] = useState('')

  /** 提交时调用外部回调,成功后由父组件关闭弹窗并清空表单。 */
  const handleSubmit = () => {
    onPublish(version, changelog)
  }

  /** 关闭时清空表单(下次打开干净态)。 */
  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setVersion('')
      setChangelog('')
    }
    onOpenChange(nextOpen)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[520px]">
        <DialogHeader>
          <DialogTitle>发布代码版本</DialogTitle>
          <DialogDescription>发布即冻结,要改需开新草稿。</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3.5">
          {/* 版本号 */}
          <div>
            <Label className="kq-label">版本号</Label>
            <Input
              value={version}
              onChange={(e) => setVersion(e.target.value)}
              placeholder={`v${latestVersion ? latestVersion + 1 : 1}`}
            />
          </div>
          {/* 变更说明 */}
          <div>
            <Label className="kq-label">变更说明</Label>
            <Textarea
              value={changelog}
              onChange={(e) => setChangelog(e.target.value)}
              placeholder="加入 ADX>25 趋势过滤,止损 ATR×1.5 → ATR×2.5"
              className="min-h-[80px]"
            />
          </div>
          {/* 冻结警告 */}
          <div className="rounded-md border border-dashed border-border-soft bg-surface-card-2 p-3 text-caption leading-relaxed text-text-secondary">
            <strong className="text-warning">⚠ 一旦发布即冻结</strong>,不可再修改。要改需开新草稿,当前已发布版本将自动归档。
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => handleOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={publishing}>
            <GitBranch className="size-3.5" aria-hidden />
            {publishing ? '发布中…' : '发布并冻结'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
