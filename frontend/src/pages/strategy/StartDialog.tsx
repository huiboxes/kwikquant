import { useState } from 'react'
import { Play } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { StrategyDetailDto } from '@/api/strategy'

/**
 * StartDialog — 启动策略对话框。
 *
 * 从 StrategyPage 提取:策略信息卡 + PAPER/LIVE 账户选择 + LIVE 高风险警告。
 * startAccount 状态内部管理('PAPER'|'LIVE'),关闭时重置为 PAPER。
 */

interface StartDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  strategy: StrategyDetailDto | null
  starting: boolean
  onStart: () => void
}

export function StartDialog(props: StartDialogProps) {
  const { open, onOpenChange, strategy, starting, onStart } = props

  // 账户选择(PAPER 默认,LIVE 需二次确认提示)
  const [startAccount, setStartAccount] = useState('PAPER')

  /** 关闭时重置账户选择。 */
  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setStartAccount('PAPER')
    }
    onOpenChange(nextOpen)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[460px]">
        <DialogHeader>
          <DialogTitle>启动策略</DialogTitle>
          <DialogDescription>Worker 上线接收行情并按策略下单。</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          {/* 策略信息卡 */}
          <div className="rounded-md border border-border-soft bg-surface-card-2 p-3.5">
            <div className="text-body-sm font-semibold text-text-primary">
              {strategy?.name ?? '…'}
            </div>
            <div className="mt-1 text-[11px] text-text-muted">
              {strategy?.symbol} · {strategy?.exchange} · {strategy?.intervalValue}
            </div>
          </div>

          {/* 账户绑定说明 */}
          <div className="text-caption leading-relaxed text-text-secondary">
            启动后 Worker 将自动接收行情并按策略下单。绑定账户:
          </div>

          {/* 账户选择器 */}
          <Select value={startAccount} onValueChange={setStartAccount}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="PAPER">PAPER · 主模拟盘</SelectItem>
              {/* LIVE 待后端 start 端点支持 accountType(TD-038),当前 disabled 不假承诺有二次确认 */}
              <SelectItem value="LIVE" disabled>
                LIVE · 实盘(待后端支持)
              </SelectItem>
            </SelectContent>
          </Select>

          {/* 诚实提示:LIVE 未接通,不假承诺二次确认(M-NEW-2) */}
          <div className="rounded-md border border-border-soft bg-surface-card-2 p-2.5 text-[11px] leading-relaxed text-text-secondary">
            实盘(LIVE)启动需后端 start 端点支持账户类型选择(TD-038),当前仅模拟盘可用。
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => handleOpenChange(false)}>
            取消
          </Button>
          <Button onClick={onStart} disabled={starting}>
            <Play className="size-3.5" aria-hidden />{' '}
            {starting ? '启动中…' : '启动'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
