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
 * FsmDialog — 策略状态机说明对话框。
 *
 * 状态流转图(草稿→就绪→运行中⇄已暂停→已停止)+ 流转规则说明。
 * 高亮**当前策略状态**对应节点(currentStatus prop)，非硬编码。
 */

interface FsmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 当前策略 status(DRAFT/READY/RUNNING/PAUSED/STOPPED/ERROR)，高亮对应节点 */
  currentStatus?: string
}

/** 状态节点(原型 FSM 顺序)。 */
const STATES = ['草稿', '就绪', '运行中', '已暂停', '已停止'] as const

/** 后端 status 枚举 → FsmDialog 节点名(ERROR 无对应，不高亮)。 */
function statusToNode(status?: string): string | null {
  switch (status) {
    case 'DRAFT':
      return '草稿'
    case 'READY':
      return '就绪'
    case 'RUNNING':
      return '运行中'
    case 'PAUSED':
      return '已暂停'
    case 'STOPPED':
      return '已停止'
    default:
      return null
  }
}

export function FsmDialog(props: FsmDialogProps) {
  const { open, onOpenChange, currentStatus } = props
  const activeNode = statusToNode(currentStatus)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[560px]">
        <DialogHeader>
          <DialogTitle>策略状态机说明</DialogTitle>
          <DialogDescription>策略状态流转规则。高亮节点为当前策略状态。</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3.5">
          {/* 状态流转图：高亮当前策略状态节点 */}
          <div>
            <div className="mb-2 text-caption-sm uppercase tracking-[0.04em] text-text-muted">
              状态流转
            </div>
            <div className="flex flex-wrap items-center gap-1.5 text-caption">
              {STATES.map((s, i, arr) => (
                <span key={s} className="flex items-center gap-1.5">
                  <span
                    className={`rounded-md border px-2.5 py-1 text-caption-sm font-medium ${
                      s === activeNode
                        ? 'border-accent bg-accent-soft text-accent'
                        : 'border-border-soft bg-surface-card-2 text-text-secondary'
                    }`}
                  >
                    {s}
                  </span>
                  {i < arr.length - 1 && (
                    <span className="text-text-muted">→</span>
                  )}
                </span>
              ))}
              <span className="ml-1 text-text-muted">↻ 重新启动</span>
            </div>
          </div>

          {/* 流转规则(strategy 视角，6 态照后端 StrategyStatus 枚举) */}
          <div className="rounded-md bg-surface-card-2 p-3 text-caption leading-relaxed text-text-secondary">
            <div className="mb-1.5 font-semibold text-text-primary">流转规则</div>
            · <strong>草稿 → 就绪</strong>:发布代码并标记就绪，策略可启动<br />
            · <strong>就绪 → 运行中</strong>:启动后开始接收行情并按规则下单<br />
            · <strong>运行中 ⇄ 已暂停</strong>:暂停/恢复，不下单但保留运行<br />
            · <strong>运行中 → 异常</strong>:策略运行出错时自动转入<br />
            · <strong>异常/已暂停 → 已停止</strong>:停止策略退出运行<br />
            · <strong>已停止 → 运行中</strong>:重新启动(用已发布代码恢复运行，可切账户)
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            关闭
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
