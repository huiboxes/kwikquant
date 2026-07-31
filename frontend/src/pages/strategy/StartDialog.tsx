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
import type { components } from '@/types/api-gen'

type ExchangeAccountView = components['schemas']['ExchangeAccountView']

/**
 * StartDialog — 启动策略对话框。
 *
 * 去 UNIQUE(user_id, exchange)后同 exchange 多账户(模拟盘+实盘并存),启动时显式选账户:
 * 下拉列该 strategy.exchange 的账户(模拟盘/实盘 + 测试网标),选后 onStart(accountId) →
 * worker token 绑 accountId。同一策略可切换账户重启(切模拟↔实盘)。
 */
interface StartDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  strategy: StrategyDetailDto | null
  accounts: ExchangeAccountView[]
  starting: boolean
  onStart: (accountId: number) => void
  /** 「先去编辑代码」次按钮回调(STOPPED 重启时显示);不传则不渲染该按钮 */
  onEditCode?: () => void
}

export function StartDialog(props: StartDialogProps) {
  const { open, onOpenChange, strategy, accounts, starting, onStart, onEditCode } = props
  const isStopped = strategy?.status === 'STOPPED'

  // 选账户:默认选当前绑账户(strategy.exchangeAccountId,切账户/PAUSED 主动打开时选回原);未绑(READY 首次)选首个
  const [accountId, setAccountId] = useState<string>('')
  const boundAccountId = strategy?.exchangeAccountId ?? null
  const firstId =
    boundAccountId != null
      ? String(boundAccountId)
      : accounts[0]?.id != null
        ? String(accounts[0].id)
        : ''
  const effectiveAccountId = accountId || firstId

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setAccountId('') // 关闭重置
    }
    onOpenChange(nextOpen)
  }

  const handleStart = () => {
    // eslint-disable-next-line no-restricted-syntax -- accountId 是 id 非金额,Select value string→number
    const id = Number(effectiveAccountId)
    if (!id) return
    onStart(id)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[460px]">
        <DialogHeader>
          <DialogTitle>{isStopped ? '重新启动策略' : '启动策略'}</DialogTitle>
          <DialogDescription>
            {isStopped
              ? '策略已停止。重新启动将使用已发布的代码版本恢复运行。如需修改代码，请先编辑并发布；如修改了交易对/交易所等配置，请确保已发布代码兼容。'
              : '策略开始接收行情并按规则下单。'}
          </DialogDescription>
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

          <div className="text-caption leading-relaxed text-text-secondary">
            启动后策略将接收行情并按规则下单。绑定账户:
          </div>

          {accounts.length === 0 ? (
            <div className="rounded-md border border-dashed border-border-soft bg-surface-card-2 p-2.5 text-[11px] text-text-secondary">
              该交易所({strategy?.exchange})暂无账户,请先在「设置 - 交易账户」录入。
            </div>
          ) : (
            <Select value={effectiveAccountId} onValueChange={setAccountId}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {accounts.map((a) => (
                  <SelectItem key={a.id} value={String(a.id)}>
                    {a.label} · {a.paperTrading ? '模拟盘' : '实盘'}
                    {a.testnet ? ' · 测试网' : ''}
                    {a.id === boundAccountId ? ' · 当前' : ''}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}

          <div className="rounded-md border border-border-soft bg-surface-card-2 p-2.5 text-[11px] leading-relaxed text-text-secondary">
            模拟盘使用虚拟资金,实盘使用真实资金。测试网账户不涉及真实资金。同一策略可切换账户重启。
          </div>
        </div>
        <DialogFooter className="sm:justify-between">
          {isStopped && onEditCode ? (
            <Button
              variant="ghost"
              className="mr-auto"
              onClick={() => {
                onEditCode()
                onOpenChange(false)
              }}
            >
              先去编辑代码
            </Button>
          ) : (
            <span className="mr-auto" />
          )}
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => handleOpenChange(false)}>
              取消
            </Button>
            <Button onClick={handleStart} disabled={starting || !effectiveAccountId}>
              <Play className="size-3.5" aria-hidden />{' '}
              {starting ? '启动中…' : isStopped ? '重新启动' : '启动'}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
