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
 * 从 StrategyPage 提取:状态流转图(草稿→就绪→运行中⇄已暂停→已停止)
 * + 流转规则文字说明 + LIVE 二次确认警告。
 */

interface FsmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** 状态节点列表(原型 FSM 顺序)。 */
const STATES = ['草稿', '就绪', '运行中', '已暂停', '已停止'] as const

export function FsmDialog(props: FsmDialogProps) {
  const { open, onOpenChange } = props

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[560px]">
        <DialogHeader>
          <DialogTitle>策略状态机说明</DialogTitle>
          <DialogDescription>策略状态流转规则与 LIVE 二次确认说明。</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3.5">
          {/* 状态流转图 */}
          <div>
            <div className="mb-2 text-[11px] uppercase tracking-[0.04em] text-text-muted">
              STATE FLOW
            </div>
            <div className="flex flex-wrap items-center gap-1.5 text-caption">
              {STATES.map((s, i, arr) => (
                <span key={s} className="flex items-center gap-1.5">
                  <span
                    className={`rounded-md border px-2.5 py-1 text-[11px] font-medium ${
                      s === '运行中'
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
            </div>
          </div>

          {/* 流转规则 */}
          <div className="rounded-md bg-surface-card-2 p-3 text-caption leading-relaxed text-text-secondary">
            <div className="mb-1.5 font-semibold text-text-primary">流转规则</div>
            · <strong>草稿 → 就绪</strong>:需先发布代码版本,发布即冻结<br />
            · <strong>就绪 → 运行中</strong>:Worker 上线接收行情并按策略下单<br />
            · <strong>运行中 ⇄ 已暂停</strong>:不停进程,只标记不下单<br />
            · <strong>已停止</strong>:终态,需重新编辑回草稿
          </div>

          {/* LIVE 高风险警告 */}
          <div className="rounded-md border border-accent bg-accent-soft p-3 text-[11px] leading-relaxed text-text-primary">
            ⚠ 切到 LIVE 账户需高风险二次确认,会触发风控闸门检查。
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
