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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useUiStore, type Exchange } from '@/stores/uiStore'
import type { CreateStrategyRequest } from '@/api/strategy'
import { PRESET_STRATEGIES } from './presetStrategies'

interface CreateStrategyDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  creating: boolean
  /** 选中预置模版 → 创建策略 + 用模版 sourceCode 建初始草稿(快速回测/当起点)。 */
  onCreate: (req: CreateStrategyRequest, opts?: { presetKey?: string; sourceCode?: string }) => void
  /** 预填 symbol(行情页"策"按钮/交易页"写策略"跳转 ?symbol= 带入),默认 BTC/USDT */
  symbol?: string
  /** 预填 marketType,默认 SPOT */
  marketType?: 'SPOT' | 'PERP'
}

/**
 * CreateStrategyDialog — 创建策略对话框(POST /api/v1/strategies)。
 *
 * 填 name + description + 交易所;symbol/marketType/intervalValue 用默认值
 * (BTC/USDT · SPOT · 1h)。交易所默认从 uiStore 取(项目基准 OKX),用户可改选 ——
 * 修复原硬编码 'BINANCE' 导致"创建策略默认 binance,切换不起作用"的根因。
 * 选预置模版 → 用其 sourceCode 建初始草稿(快速回测/当起点)。
 *
 * 与后端契约的差异:
 *  - 后端 CreateStrategyRequest 这些字段必填,不能不给 → 创建时填默认值
 *  - 后端无"更新策略运行配置"端点 → BottomControlBar 改 symbol/interval/exchange
 *    走 fork 创建新策略;原策略 exchange 创建后落库不可改
 *  - parameters 字段产品上无意义(参数直接写代码里),传默认 "{}"
 *  - exchange 不含 PAPER:PAPER 是账户类型(模拟盘),不是行情来源交易所
 */
export function CreateStrategyDialog(props: CreateStrategyDialogProps) {
  const { open, onOpenChange, creating, onCreate, symbol, marketType } = props

  // 交易所默认从 uiStore 取(项目基准 OKX,对齐后端 application.yaml + AuthService 注册
  // 建 OKX 模拟盘);用户可在对话框内改选,提交时用 state exchange。
  const storeExchange = useUiStore((s) => s.exchange)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [exchange, setExchange] = useState<Exchange>(storeExchange)
  const [presetKey, setPresetKey] = useState<string | undefined>(undefined)

  /** 关闭时重置表单(交易所回 store 当前值,下次打开取最新基准)。 */
  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setName('')
      setDescription('')
      setExchange(useUiStore.getState().exchange)
      setPresetKey(undefined)
    }
    onOpenChange(nextOpen)
  }

  /** 选预置模版:填 name/description + 记 presetKey(source 在父建草稿时用)。 */
  const handlePickPreset = (key: string) => {
    const p = PRESET_STRATEGIES.find((s) => s.key === key)
    if (!p) return
    setPresetKey(key)
    setName(p.name)
    setDescription(p.description)
  }

  const handleSubmit = () => {
    onCreate(
      {
        name: name.trim(),
        description: description.trim(),
        // 预填 symbol/marketType(从 URL query 带入,行情页"策"按钮/交易页"写策略"跳转);默认 BTC/USDT · SPOT
        symbol: symbol ?? 'BTC/USDT',
        // 交易所从 state 取(uiStore 默认 OKX,用户可改)—— 原 hardcode 'BINANCE' 已移除
        exchange,
        marketType: marketType ?? 'SPOT',
        intervalValue: '1h',
        // 参数产品上无意义,用户直接写代码里
        parameters: '{}',
      },
      presetKey ? { presetKey } : undefined,
    )
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[480px]">
        <DialogHeader>
          <DialogTitle>创建策略</DialogTitle>
          <DialogDescription>
            新建一个策略,创建后在编辑器里编写代码、配置运行参数。可从预置模版起步。
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3.5">
          {/* 预置模版(快速回测 / 当起点) */}
          <div>
            <Label className="kq-label">从预置模版起步(可选)</Label>
            <div className="flex flex-wrap gap-1.5">
              {PRESET_STRATEGIES.map((p) => (
                <button
                  key={p.key}
                  type="button"
                  onClick={() => handlePickPreset(p.key)}
                  className={`rounded-pill border px-sm py-xxs text-caption transition-colors ${
                    presetKey === p.key
                      ? 'border-accent bg-accent-soft text-text-primary'
                      : 'border-border-soft bg-surface-card-2 text-text-secondary hover:bg-surface-3'
                  }`}
                  title={p.description}
                >
                  {p.name}
                </button>
              ))}
              <button
                type="button"
                onClick={() => {
                  setPresetKey(undefined)
                  setName('')
                  setDescription('')
                }}
                className="rounded-pill px-xxs text-caption text-text-muted transition-colors hover:text-text-primary"
              >
                清空
              </button>
            </div>
          </div>

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

          {/* 交易所(默认 uiStore OKX,用户可改;创建后落库不可改,改走 fork ) */}
          <div>
            <Label className="kq-label">交易所</Label>
            <Select value={exchange} onValueChange={(v) => setExchange(v as Exchange)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="OKX">OKX</SelectItem>
                <SelectItem value="BINANCE">BINANCE</SelectItem>
                <SelectItem value="BITGET">BITGET</SelectItem>
              </SelectContent>
            </Select>
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
