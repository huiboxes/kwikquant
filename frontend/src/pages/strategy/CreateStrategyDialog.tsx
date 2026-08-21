import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
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
import { cn } from '@/lib/utils'
import { SymbolSelect } from '@/components/SymbolSelect'
import type { CreateStrategyRequest } from '@/api/strategy'

interface CreateStrategyDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  creating: boolean
  /** 创建策略 + 建初始草稿。opts.sourceCode 仅内部 fork("另存为新策略")继承源码用；
   *  模板起步走模板库页(/templates,fork 官方模板)。 */
  onCreate: (req: CreateStrategyRequest, opts?: { sourceCode?: string }) => void
  /** 预填 symbol(行情页"策"按钮/交易页"写策略"跳转 ?symbol= 带入)，默认 BTC/USDT */
  symbol?: string
  /** 预填 marketType，默认 SPOT */
  marketType?: 'SPOT' | 'PERP'
}

// 周期常量(标的下拉由 SymbolSelect 内部 useTradableSymbols 拉取，不再 fallback 写死)
const TIMEFRAMES = ['1m', '5m', '15m', '1h', '4h', '1d']

/**
 * CreateStrategyDialog — 创建策略对话框(POST /api/v1/strategies)。
 *
 * 填 name + description + 交易所/标的/周期。交易所默认从 uiStore 取(项目基准 OKX),
 * 标的从 usePairs(exchange, marketType) 拉真(空 fallback 5 主流)，周期默认 1h。
 * 为避免"每次改标的都要 fork 新策略"，创建时直接选对 symbol/interval,
 * 避免后续改走 fork(策略 exchange/symbol/interval 创建后落库不可改)。
 * 从模板起步走模板库页(/templates,fork 官方模板 + 自动首回测)，本 dialog 只建空策略。
 *
 * 与后端契约的差异:
 *  - 后端 CreateStrategyRequest 这些字段必填，不能不给 → 创建时填默认值
 *  - 后端无"更新策略运行配置"端点 → BottomControlBar 改 symbol/interval/exchange
 *    走 fork 创建新策略；故创建时选对很关键
 *  - parameters 字段产品上无意义(参数直接写代码里)，传默认 "{}"
 *  - exchange 不含 PAPER:PAPER 是账户类型(模拟盘)，不是行情来源交易所
 */
export function CreateStrategyDialog(props: CreateStrategyDialogProps) {
  const { open, onOpenChange, creating, onCreate, symbol: propSymbol, marketType: propMarketType } = props
  const navigate = useNavigate()

  // 交易所默认从 uiStore 取(项目基准 OKX，对齐后端 application.yaml + AuthService);
  // 标的/周期默认 BTC/USDT · 1h(propSymbol 从 URL query 预填)。用户可改选。
  const storeExchange = useUiStore((s) => s.exchange)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [exchange, setExchange] = useState<Exchange>(storeExchange)
  const [symbol, setSymbol] = useState(propSymbol ?? 'BTC/USDT')
  const [interval, setInterval] = useState('1h')
  // marketType 从 prop 预填(交易页 PERP 态"写策略"带 ?marketType=PERP)。
  // SymbolSelect 据此拉对应市场标的列表。
  const [marketType, setMarketType] = useState<'SPOT' | 'PERP'>(propMarketType ?? 'SPOT')
  // 合约参数(策略级绑定):PERP 才有，创建时定死；SPOT null。后端已支持 CROSS。
  const [marginMode, setMarginMode] = useState<'ISOLATED' | 'CROSS'>('ISOLATED')
  const [leverage, setLeverage] = useState(10)

  // 标的下拉由 SymbolSelect 内部 useTradableSymbols 提供(24h 成交额排序 + 搜索 + strip)，见下方 JSX

  /** 关闭时重置表单(交易所回 store 当前值，标的回 propSymbol 默认)。 */
  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setName('')
      setDescription('')
      setExchange(useUiStore.getState().exchange)
      setSymbol(propSymbol ?? 'BTC/USDT')
      setInterval('1h')
      setMarketType(propMarketType ?? 'SPOT')
      setMarginMode('ISOLATED')
      setLeverage(10)
    }
    onOpenChange(nextOpen)
  }

  const handleSubmit = () => {
    onCreate({
      name: name.trim(),
      description: description.trim(),
      // 标的/周期从 state 取(用户选)；原 hardcode BTC/USDT · 1h 已移除
      symbol,
      exchange,
      marketType,
      // PERP 传值，SPOT 传 null(后端 record marginMode/leverage nullable,api-gen 已 nullable)
      marginMode: marketType === 'PERP' ? marginMode : null,
      leverage: marketType === 'PERP' ? leverage : null,
      intervalValue: interval,
      // 参数产品上无意义，用户直接写代码里
      parameters: '{}',
    })
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[480px]">
        <DialogHeader>
          <DialogTitle>创建策略</DialogTitle>
          <DialogDescription>
            新建一个空策略，创建后在编辑器里编写代码。想从模板起步，
            {/* 模板引导给可点入口(红线②):关 dialog 直达模板库，不留纯文字死路 */}
            <button
              type="button"
              className="font-semibold text-accent hover:underline"
              onClick={() => {
                handleOpenChange(false)
                navigate('/templates')
              }}
            >
              去模板库 fork
            </button>
            。
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3.5">
          {/* 市场类型 segment:SPOT 现货 / PERP 合约. 照交易页原型 line 81-88.
              市场类型是策略根属性(创建后落库不可改)，决定整个表单形态
              (PERP 显合约参数). 放最顶：用户一进来先选，不靠模版被动带. */}
          <div className="flex gap-1 rounded-lg border border-border-soft bg-surface-card-2 p-1">
            {(['SPOT', 'PERP'] as const).map((m) => {
              const active = marketType === m
              return (
                <button
                  key={m}
                  type="button"
                  onClick={() => {
                    setMarketType(m)
                    if (m === 'SPOT') {
                      setMarginMode('ISOLATED')
                      setLeverage(10)
                    }
                  }}
                  className={cn(
                    'kq-press flex-1 rounded-md py-1.5 text-body-sm font-bold tracking-[0.04em] transition-all',
                    active
                      ? 'bg-accent text-on-accent'
                      : 'text-text-muted hover:text-text-secondary',
                  )}
                >
                  {m === 'SPOT' ? '现货' : '合约'}
                </button>
              )
            })}
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
              placeholder="快慢均线交叉，金叉做多、死叉平仓"
              className="min-h-[72px]"
            />
          </div>

          {/* 运行配置：交易所 + 周期(一行两列) + 标的(满宽)。
              创建后落库不可改，改走 fork — 故创建时选对 */}
          <div className="grid grid-cols-2 gap-3">
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
            <div>
              <Label className="kq-label">周期</Label>
              <Select value={interval} onValueChange={setInterval}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {TIMEFRAMES.map((t) => (
                    <SelectItem key={t} value={t}>{t}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="col-span-2">
              <Label className="kq-label">标的</Label>
              <SymbolSelect
                value={symbol}
                onChange={setSymbol}
                exchange={exchange}
                marketType={marketType}
                trigger="dialog"
              />
            </div>
          </div>

          {/* 合约参数(PERP 才显，策略级绑定):保证金模式 + 杠杆。创建时定死，启动只读确认。 */}
          {marketType === 'PERP' && (
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label className="kq-label">保证金模式</Label>
                <div className="grid grid-cols-2 gap-1">
                  {([
                    { key: 'ISOLATED' as const, label: '逐仓' },
                    { key: 'CROSS' as const, label: '全仓' },
                  ]).map((m) => (
                    <button
                      key={m.key}
                      type="button"
                      onClick={() => setMarginMode(m.key)}
                      title={
                        m.key === 'CROSS'
                          ? '全仓模式：账户全部可用余额作为担保，任一仓位亏损可能连累其他仓位被强平'
                          : undefined
                      }
                      className={`rounded-lg border py-1.5 text-caption font-bold transition-colors ${
                        marginMode === m.key
                          ? 'border-accent bg-accent-soft text-accent'
                          : 'border-border-soft bg-surface-card-2 text-text-muted hover:bg-surface-3'
                      }`}
                    >
                      {m.label}
                    </button>
                  ))}
                </div>
              </div>
              <div>
                <Label className="kq-label">杠杆倍数</Label>
                <Input
                  type="text"
                  inputMode="numeric"
                  value={String(leverage)}
                  onChange={(e) => {
                    const v = parseInt(e.target.value || '1', 10)
                    setLeverage(Math.max(1, Math.min(125, Number.isNaN(v) ? 1 : v)))
                  }}
                  className="kq-mono-row h-9"
                />
                {/* 杠杆预设(创建场景 5 档足够，对齐下单面板体验；可提取共享常量到 lib/) */}
                <div className="mt-1 flex gap-1">
                  {[2, 5, 10, 25, 50].map((p) => (
                    <button
                      key={p}
                      type="button"
                      onClick={() => setLeverage(p)}
                      className={cn(
                        'kq-press flex-1 rounded-sm border py-1 text-caption-xs font-bold transition-all',
                        leverage === p
                          ? 'border-accent bg-accent-soft text-accent'
                          : 'border-border-soft bg-surface-card-2 text-text-muted hover:text-text-secondary',
                      )}
                    >
                      {p}x
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

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
