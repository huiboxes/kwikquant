import { useState } from 'react'
import { Plus } from 'lucide-react'
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
import type { CreateStrategyRequest } from '@/api/strategy'

interface CreateStrategyDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  creating: boolean
  onCreate: (req: CreateStrategyRequest) => void
  /** 预填 symbol(行情页"策"按钮/交易页"写策略"跳转 ?symbol= 带入),默认 BTC/USDT */
  symbol?: string
  /** 预填 marketType,默认 SPOT */
  marketType?: 'SPOT' | 'PERP'
}

/**
 * CreateStrategyDialog — 创建策略对话框(POST /api/v1/strategies)。
 *
 * 只填 name + description;symbol/exchange/marketType/intervalValue 用默认值
 * (BTC/USDT · BINANCE · SPOT · 1h),用户后续在编辑器下方 BottomControlBar 配置。
 *
 * honest(契约缺口,记 TD-040/042):
 *  - 后端 CreateStrategyRequest 这些字段必填,不能不给 → 创建时填默认值
 *  - 后端无"更新策略运行配置"端点 → BottomControlBar 改 symbol/interval 走 fork 创建新策略(TD-039)
 *  - parameters 字段产品上无意义(参数直接写代码里),传默认 "{}"
 *  - exchange 不含 PAPER:PAPER 是账户类型(模拟盘),不是行情来源交易所(TD-042)
 */
export function CreateStrategyDialog(props: CreateStrategyDialogProps) {
  const { open, onOpenChange, creating, onCreate, symbol, marketType } = props

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  /** 关闭时重置表单。 */
  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setName('')
      setDescription('')
    }
    onOpenChange(nextOpen)
  }

  const handleSubmit = () => {
    onCreate({
      name: name.trim(),
      description: description.trim(),
      // 预填 symbol/marketType(从 URL query 带入,行情页"策"按钮/交易页"写策略"跳转);默认 BTC/USDT · SPOT
      symbol: symbol ?? 'BTC/USDT',
      exchange: 'BINANCE',
      marketType: marketType ?? 'SPOT',
      intervalValue: '1h',
      // 参数产品上无意义,用户直接写代码里(TD-042)
      parameters: '{}',
    })
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[480px]">
        <DialogHeader>
          <DialogTitle>创建策略</DialogTitle>
          <DialogDescription>
            新建一个策略,创建后在编辑器里编写代码、配置运行参数。
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3.5">
          <div>
            <Label className="kq-label">策略名称</Label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="BTC 均线交叉"
            />
          </div>

          <div>
            <Label className="kq-label">策略描述</Label>
            <Textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="快慢均线交叉,金叉做多、死叉平仓"
              className="min-h-[72px]"
            />
          </div>

        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => handleOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleSubmit} disabled={creating || !name.trim()}>
            <Plus className="size-3.5" aria-hidden /> {creating ? '创建中…' : '创建策略'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
