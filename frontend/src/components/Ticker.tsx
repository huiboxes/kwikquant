import { useMarketStore } from '@/stores/marketStore'

/**
 * Ticker — 价格跳动(轻量版,无 flash)。
 *
 * 依赖全局 tickerTick(marketStore,1.8s 心跳),用 sin 抖动模拟价格跳动;涨跌染色。
 * 对齐原型 ui.jsx Ticker(L195-201)。比 LivePrice 轻(无方向翻转 flash)。
 * 用于行情网格的 ticker 卡、K 线 OHLC 脚等不需要强闪烁的场景。
 */
function formatNum(v: number, dp: number): string {
  return v.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp })
}

export function Ticker({
  base,
  chg,
  dp = 2,
  className,
}: {
  base: number
  chg: number
  dp?: number
  className?: string
}) {
  const tickerTick = useMarketStore((s) => s.tickerTick)
  const flick = Math.sin(tickerTick * 1.7 + base) * 0.0008
  const v = base * (1 + flick)
  return (
    <span
      className={`kq-mono-row ${className ?? ''}`}
      style={{ color: chg >= 0 ? 'var(--up)' : 'var(--down)' }}
    >
      {formatNum(v, dp)}
    </span>
  )
}
