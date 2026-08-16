import { useState } from 'react'
import { toast } from 'sonner'
import { AlertTriangle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog'
import type { useCreateAccount } from '@/hooks/useAccounts'
import type { components } from '@/types/api-gen'
import { useUiStore, type Exchange } from '@/stores/uiStore'

type CreateAccountRequest = components['schemas']['CreateAccountRequest']

/**
 * AddAccountDialog — 接入交易所账户(共享组件,从 PortfolioPage 抽出)。
 * 模拟盘/实盘 视觉强区分(配色 + badge);破坏性确认在调用方(AccountCard 删除/重置)。
 *
 * 文案过滤:双选按钮中文 模拟盘/实盘;删 基准行情撮合/基准交易所 实现泄露;
 * 模拟盘 toast `10 万虚拟资金 · 可随时重来`。
 * 内部 type state 仍用 'PAPER'/'LIVE' 字符串(组件内部,非用户可见)。
 */
export function AddAccountDialog({
  open,
  onOpenChange,
  createAcc,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  createAcc: ReturnType<typeof useCreateAccount>
}) {
  const [type, setType] = useState<'PAPER' | 'LIVE'>('PAPER')
  // 交易所默认从 uiStore 取(项目基准 OKX,对齐后端 application.yaml + AuthService 注册
  // 建 OKX 模拟盘)—— 原 hardcode 'BINANCE' 已移除,修复"默认 binance"根因。
  const storeExchange = useUiStore((s) => s.exchange)
  const [exchange, setExchange] = useState<Exchange>(storeExchange)
  const [label, setLabel] = useState('主账户')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [passphrase, setPassphrase] = useState('')
  const [testnet, setTestnet] = useState(false)
  const isPaper = type === 'PAPER'

  const reset = () => {
    setType('PAPER'); setExchange(useUiStore.getState().exchange); setLabel('主账户')
    setApiKey(''); setApiSecret(''); setPassphrase(''); setTestnet(false)
  }

  const handleSubmit = () => {
    const body: CreateAccountRequest = {
      exchange,
      paperTrading: isPaper,
      testnet: isPaper ? false : testnet,
      label,
      apiKey: isPaper ? '' : apiKey,
      apiSecret: isPaper ? '' : apiSecret,
      passphrase: isPaper ? '' : passphrase,
    }
    createAcc.mutate(body, {
      onSuccess: () => {
        toast.success(isPaper ? '模拟盘已就绪 · 10 万虚拟资金' : 'API 密钥已加密存储')
        onOpenChange(false)
        reset()
      },
      onError: (err: unknown) => {
        const code = (err as { code?: number } | null)?.code
        if (code === 4009) {
          toast.error('该交易所已有账户,请先在交易账户列表删除旧账户再重录(同交易所仅一个账户)')
        } else {
          toast.error('接入失败,请重试')
        }
      },
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>接入交易所账户</DialogTitle>
          <DialogDescription>录入 API 密钥接入实盘,或创建模拟盘(10 万虚拟资金)。</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          <div>
            <span className="kq-label">类型</span>
            <div className="mt-1.5 grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setType('PAPER')}
                className={`rounded-lg border-2 px-2.5 py-2.5 text-caption font-semibold transition-all ${
                  type === 'PAPER'
                    ? 'border-up bg-up/10 text-up'
                    : 'border-border-soft bg-surface-card-2 text-text-secondary'
                }`}
              >
                模拟盘
              </button>
              <button
                type="button"
                onClick={() => setType('LIVE')}
                className={`rounded-lg border-2 px-2.5 py-2.5 text-caption font-semibold transition-all ${
                  type === 'LIVE'
                    ? 'border-accent bg-accent-soft text-accent'
                    : 'border-border-soft bg-surface-card-2 text-text-secondary'
                }`}
              >
                实盘
              </button>
            </div>
          </div>
          {!isPaper && (
            <div>
              <span className="kq-label">环境</span>
              <div className="mt-1.5 grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => setTestnet(false)}
                  className={`rounded-lg border-2 px-2.5 py-2 text-caption font-semibold transition-all ${
                    !testnet
                      ? 'border-accent bg-accent-soft text-accent'
                      : 'border-border-soft bg-surface-card-2 text-text-secondary'
                  }`}
                >
                  生产
                </button>
                <button
                  type="button"
                  onClick={() => setTestnet(true)}
                  className={`rounded-lg border-2 px-2.5 py-2 text-caption font-semibold transition-all ${
                    testnet
                      ? 'border-up bg-up/10 text-up'
                      : 'border-border-soft bg-surface-card-2 text-text-secondary'
                  }`}
                >
                  测试网
                </button>
              </div>
              {testnet && (
                <p className="mt-1.5 text-micro text-text-secondary">
                  使用交易所测试网密钥(如 OKX demo),不涉及真实资金。
                </p>
              )}
            </div>
          )}
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <span className="kq-label">交易所</span>
              <Select value={exchange} onValueChange={(v) => setExchange(v as Exchange)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="BINANCE">BINANCE</SelectItem>
                  <SelectItem value="OKX">OKX</SelectItem>
                  <SelectItem value="BITGET">BITGET</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1.5">
              <span className="kq-label">账户标签</span>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} />
            </div>
          </div>
          {!isPaper && (
            <div className="flex flex-col gap-2.5">
              <div className="flex flex-col gap-1.5">
                <span className="kq-label">API 密钥</span>
                <Input value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="粘贴 API 密钥 · 加密存储" />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="kq-label">API Secret</span>
                <Input type="password" value={apiSecret} onChange={(e) => setApiSecret(e.target.value)} placeholder="粘贴 Secret · 加密存储" />
              </div>
              {(exchange === 'OKX' || exchange === 'BITGET') && (
                <div className="flex flex-col gap-1.5">
                  <span className="kq-label">Passphrase</span>
                  <Input type="password" value={passphrase} onChange={(e) => setPassphrase(e.target.value)} placeholder={`${exchange} 必填 · 加密存储`} />
                </div>
              )}
            </div>
          )}
          <div className="rounded-lg border border-dashed border-border-soft bg-surface-card-2 p-2.5 text-caption-sm leading-[1.5] text-text-muted">
            {isPaper ? (
              <>模拟盘 · 10 万虚拟资金 · 可随时重来。现货/合约在下单时选择。</>
            ) : (
              <>
                <AlertTriangle className="mr-1 inline size-3" aria-hidden />
                实盘 API 密钥加密存储,不完整显示,仅末 4 位。现货/合约在下单时选择。
              </>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>取消</Button>
          <Button size="sm" disabled={createAcc.isPending} onClick={handleSubmit}>接入</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
