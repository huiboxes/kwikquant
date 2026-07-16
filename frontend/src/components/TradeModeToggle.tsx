import { useUiStore, type TradeMode } from '@/stores/uiStore'
import { useState } from 'react'
import { ConfirmDialog } from '@/components/ConfirmDialog'

/**
 * TradeModeToggle — 紧凑的 PAPER/LIVE 模式切换控件。
 * 用于 TopBar / Dashboard 等需要全局模式切换的位置。
 * LIVE 切换需确认（首次 per session）。
 */
export function TradeModeToggle() {
  const { tradeMode, setTradeMode, liveConfirmedThisSession, setLiveConfirmedThisSession } =
    useUiStore()
  const [confirmOpen, setConfirmOpen] = useState(false)

  const handleSelect = (mode: TradeMode) => {
    if (mode === tradeMode) return
    if (mode === 'LIVE' && !liveConfirmedThisSession) {
      setConfirmOpen(true)
      return
    }
    setTradeMode(mode)
  }

  const confirmLive = () => {
    setLiveConfirmedThisSession(true)
    setTradeMode('LIVE')
    setConfirmOpen(false)
  }

  return (
    <>
      <div
        className="inline-flex rounded-lg border border-border-soft bg-surface-card p-0.5"
        role="radiogroup"
        aria-label="交易模式"
      >
        {(['PAPER', 'LIVE'] as const).map((m) => {
          const active = tradeMode === m
          return (
            <button
              key={m}
              role="radio"
              aria-checked={active}
              onClick={() => handleSelect(m)}
              className={`rounded-md px-3 py-1 text-xs font-medium transition-colors ${
                active
                  ? m === 'PAPER'
                    ? 'bg-up/15 text-up'
                    : 'bg-accent/15 text-accent'
                  : 'text-text-muted hover:text-text-secondary'
              }`}
            >
              {m === 'PAPER' ? '模拟' : '实盘'}
            </button>
          )
        })}
      </div>
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="切换到实盘模式"
        description="实盘模式将使用真实资金进行交易，请确保您已充分了解风险。"
        confirmLabel="确认切换"
        destructive
        onConfirm={confirmLive}
      />
    </>
  )
}
