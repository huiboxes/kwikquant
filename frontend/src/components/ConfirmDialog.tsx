import { useEffect, type ReactNode } from 'react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'

/**
 * ConfirmDialog — 确认对话框封装(spec §5 共享组件 #5,F12.2)。
 *
 * F12.2: Modal overlay + 居中卡片,取消(次要)+ 确认(主要/危险色),
 *   危险操作(删除/停止实盘)用 destructive 确认按钮。ESC 关闭,Enter 确认。
 *
 * controlled:open + onOpenChange + onConfirm。loading 态禁双击。
 *
 * children:可选,插入到 description 与 footer 之间的额外内容(如 PERP 平仓时
 * 显示杠杆/保证金/强平价等合约参数,见 TradingPage 阶段3.5)。
 */
export interface ConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  confirmLabel?: string
  cancelLabel?: string
  destructive?: boolean
  loading?: boolean
  onConfirm: () => void
  children?: ReactNode
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = '确认',
  cancelLabel = '取消',
  destructive = false,
  loading = false,
  onConfirm,
  children,
}: ConfirmDialogProps) {
  // Enter 确认(焦点在 Dialog 内时),ESC 由 shadcn Dialog 默认行为关闭
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Enter' && !loading) {
        e.preventDefault()
        onConfirm()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, loading, onConfirm])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        {children}
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button
            variant={destructive ? 'destructive' : 'default'}
            onClick={onConfirm}
            disabled={loading}
          >
            {loading ? '处理中…' : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
