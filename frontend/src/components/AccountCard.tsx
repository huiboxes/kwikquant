import { Trash2, RotateCcw } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { SparklineChart } from '@/components/charts/SparklineChart'
import { useAccountBalance } from '@/hooks/useAccounts'
import { toDecimal, formatMoney } from '@/lib/money'
import type { components } from '@/types/api-gen'

type ExchangeAccountView = components['schemas']['ExchangeAccountView']

/**
 * AccountCard — 单个交易所账户卡(共享组件,从 PortfolioPage 抽出)。
 *
 * 双态(props 区分):
 *  - managed(传 onReset/onDelete):Settings 交易账户 tab 用,显重置(仅模拟盘)+删除按钮。
 *  - readonly(不传回调):PortfolioPage 用,纯展示余额/badge。
 *
 * 文案过滤(memory feedback_copy_user_language_no_impl_leak):
 *  - 徽章中文 `模拟`/`● 实盘`,不泄露 PAPER/LIVE 枚举。
 *  - 删 `基准行情`/`交易所维护余额` 等实现泄露文本(余额就是数字,不解释来源)。
 *  - `API key 加密存储 · 仅露末4位` 保留(UI 行为说明,非后端机制)。
 */
export function AccountCard({
  acc,
  onReset,
  onDelete,
}: {
  acc: ExchangeAccountView
  onReset?: () => void
  onDelete?: () => void
}) {
  const { data: balance } = useAccountBalance(acc.id)
  const isPaper = acc.paperTrading
  const isTestnet = !isPaper && (acc.testnet ?? false)
  const usdt = balance?.currencies?.USDT
  const equity = usdt?.total ?? 0
  const free = usdt?.free ?? 0
  const used = usdt?.used ?? 0
  const managed = onReset != null || onDelete != null

  return (
    <Card className="p-5" style={{ borderTop: `3px solid ${isPaper || isTestnet ? 'var(--up)' : 'var(--accent)'}` }}>
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2">
            {isPaper ? (
              <span className="kq-paper-badge">模拟</span>
            ) : isTestnet ? (
              <span className="kq-paper-badge">沙盒</span>
            ) : (
              <span className="kq-live-badge">● 实盘</span>
            )}
            <strong className="text-body font-bold text-text-primary">{acc.label}</strong>
          </div>
          <div className="mt-1 text-[11px] text-text-muted">{acc.exchange}</div>
        </div>
        {managed && (
          <div className="flex gap-1.5">
            {onDelete && (
              <Button variant="ghost" size="sm" className="text-down" onClick={onDelete}>
                <Trash2 className="size-3.5" aria-hidden />
                删除
              </Button>
            )}
          </div>
        )}
      </div>
      {/* 余额网格 */}
      <div className="mt-3.5 grid grid-cols-2 gap-2.5 border-y border-border-soft py-3">
        <div>
          <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">总权益</div>
          <div className="kq-mono-row text-[20px] font-bold">{formatMoney(toDecimal(equity))}</div>
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-[0.05em] text-text-muted">可用 / 冻结</div>
          <div className="kq-mono-row text-[13px] font-bold">
            {formatMoney(toDecimal(free))}{' '}
            <span className="text-warning">/ {formatMoney(toDecimal(used))}</span>
          </div>
        </div>
      </div>
      {/* Sparkline */}
      <div className="mt-2.5">
        <SparklineChart
          data={[1, 2, 4, 3, 5, 6, 5, 7, 8, 7, 9]}
          width={240}
          height={32}
          color={isPaper ? 'var(--up)' : 'var(--accent)'}
        />
      </div>
      {/* 底部:仅模拟盘 + managed 态显重置按钮;无实现泄露文案 */}
      <div className="mt-2.5 flex items-center justify-end text-[11px] text-text-muted">
        {isPaper && managed && onReset && (
          <Button variant="ghost" size="sm" className="text-warning" onClick={onReset}>
            <RotateCcw className="size-3.5" aria-hidden />
            重置
          </Button>
        )}
      </div>
      {!isPaper && (
        <div className="mt-2 text-[10px] text-text-muted">
          API key: <span className="kq-mono-row">{acc.apiKey}</span>(加密存储 · 仅露末 4 位)
        </div>
      )}
    </Card>
  )
}
