import { useState } from 'react'
import { Trash2, RotateCcw } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useAccountBalance } from '@/hooks/useAccounts'
import { toDecimal, formatMoney } from '@/lib/money'
import type { components } from '@/types/api-gen'

type ExchangeAccountView = components['schemas']['ExchangeAccountView']

/**
 * AccountCard — 单个交易所账户卡(共享组件，PortfolioPage readonly + SettingsPage managed)。
 *
 * 反 AI 味(memory frontend_exhaustive_optimization_loop):删原型 border-top 彩色杠(典型
 * vibecoding 产物，DESIGN.md 未强制),PAPER/LIVE 靠 badge + 背景微差(模拟盘 surface-card-2 /
 * 实盘 surface-card)区分，CLAUDE.md PAPER/LIVE 强区分红线不靠彩色杠。
 *
 * API key 末4位居头部右侧(识别锚)，删"加密存储·仅露末4位"实现泄露文案(memory
 * feedback_copy_user_language_no_impl_leak)；后端 maskApiKey 已脱敏返 "...xxxx"，前端直接展示。
 *
 * 非 USDT 资产折叠展示(不折算估值，避免前端 ticker 耦合，折算留 follow-up)；模拟盘不显
 * 重置(仅 managed 态)；删原型 Sparkline 假数据后遗留的底部空 flex div 修 gap。
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
  const [showAllNonUsdt, setShowAllNonUsdt] = useState(false)
  const { data: balance } = useAccountBalance(acc.id)
  const isPaper = acc.paperTrading
  const isTestnet = !isPaper && (acc.testnet ?? false)
  const currencies = balance?.currencies ?? {}
  const usdt = currencies.USDT
  const equity = usdt?.total ?? 0
  const free = usdt?.free ?? 0
  const used = usdt?.used ?? 0
  const managed = onReset != null || onDelete != null
  const nonUsdtKeys = Object.keys(currencies).filter((k) => k !== 'USDT')

  return (
    <Card className="p-5">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            {isPaper ? (
              <span className="kq-paper-badge">模拟</span>
            ) : isTestnet ? (
              <span className="kq-paper-badge">测试网</span>
            ) : (
              <span className="kq-live-badge">实盘</span>
            )}
            <strong className="truncate text-body font-bold text-text-primary">{acc.label}</strong>
          </div>
          <div className="mt-1 text-caption-sm text-text-muted">{acc.exchange}</div>
        </div>
        <div className="flex flex-col items-end gap-1">
          {!isPaper && (
            <span className="kq-mono-row text-caption-xs text-text-muted">密钥 {acc.apiKey}</span>
          )}
          {managed && (
            <div className="flex gap-1.5">
              {isPaper && onReset && (
                <Button variant="ghost" size="sm" className="text-warning" onClick={onReset}>
                  <RotateCcw className="size-3.5" aria-hidden />
                  重置
                </Button>
              )}
              {onDelete && (
                <Button variant="ghost" size="sm" className="text-down" onClick={onDelete}>
                  <Trash2 className="size-3.5" aria-hidden />
                  删除
                </Button>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 余额网格 */}
      <div className="mt-3.5 grid grid-cols-2 gap-2.5 border-y border-border-soft py-3">
        <div>
          <div className="text-caption-xs uppercase tracking-[0.05em] text-text-muted">总权益</div>
          <div className="kq-mono-row text-kpi font-bold">{formatMoney(toDecimal(equity))}</div>
        </div>
        <div>
          <div className="text-caption-xs uppercase tracking-[0.05em] text-text-muted">可用 / 冻结</div>
          <div className="kq-mono-row text-body-sm font-bold">
            {formatMoney(toDecimal(free))}{' '}
            <span className="text-warning">/ {formatMoney(toDecimal(used))}</span>
          </div>
        </div>
      </div>

      {/* 非 USDT 资产：显前 3 个(默认可见，不折叠)+ 查看全部链接(可发现，品牌橙)。
          不折算估值(守"不假装管理"口径)，几十币种展开后可滚动。 */}
      {nonUsdtKeys.length > 0 && (
        <div className="mt-2.5">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 kq-mono-row text-caption-sm text-text-muted">
            {nonUsdtKeys.slice(0, 3).map((k) => {
              const b = currencies[k]
              return (
                <span key={k}>
                  {k}{' '}
                  <span className="text-text-secondary">
                    {formatMoney(toDecimal(b?.total ?? 0), { dp: 4 })}
                  </span>
                </span>
              )
            })}
          </div>
          {nonUsdtKeys.length > 3 && (
            <div>
              <button
                type="button"
                onClick={() => setShowAllNonUsdt((o) => !o)}
                className="mt-1 text-caption-sm text-accent-warm hover:underline"
              >
                {showAllNonUsdt ? '收起' : `查看全部 ${nonUsdtKeys.length} 种 →`}
              </button>
              {showAllNonUsdt && (
                <div className="mt-1.5 max-h-[120px] space-y-1 overflow-auto">
                  {nonUsdtKeys.map((k) => {
                    const b = currencies[k]
                    return (
                      <div key={k} className="kq-mono-row text-caption-sm text-text-muted">
                        {k} · 可用 {formatMoney(toDecimal(b?.free ?? 0), { dp: 4 })} / 冻结{' '}
                        {formatMoney(toDecimal(b?.used ?? 0), { dp: 4 })}
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </Card>
  )
}
