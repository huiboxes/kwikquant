import { useEffect, useMemo, useRef } from 'react'
import { useMarketStore } from '@/stores/marketStore'
import { toDecimal, formatMoney } from '@/lib/money'

/**
 * LivePrice — 实时价格(WS tick 驱动 + 未连兜底抖动)。
 *
 * 优先读 marketStore.ticks[symbol].last(WS 实时 tick,BigDecimal string → decimal.js);
 * 无 tick(WS 未连/未推送)时降级:base(REST 快照)+ tickerTick 心跳 sin 抖动假装跳动。
 * 翻转方向(涨/跌)时 DOM 加 .kq-flash class(0.8s 背景闪烁,index.css 已定义)。
 *
 * 金额红线:价格走 toDecimal + formatMoney(decimal.js),不直接 number 运算。后端 BigDecimal 实际序列化为
 * JSON number(Jackson 默认,无 @JsonFormat/全局配),非 string — 金额红线缺口(精度 >2^53 丢),长期 TD
 * 后端 @JsonFormat(shape=STRING) 或全局 Jackson 配 BigDecimal→string,届时无需改本组件(toDecimal 兼容)。
 * 抖动系数(flick)是 number 非金额,decimal.times(number) 不违反红线(金额是 Decimal,不是 number)。
 */
export function LivePrice({
  symbol,
  base,
  dp = 2,
  className,
}: {
  symbol: string
  /** REST 快照价(BigDecimal string;WS tick 来了优先用 tick.last)。 */
  base: string
  dp?: number
  className?: string
}) {
  const tick = useMarketStore((s) => s.ticks[symbol])
  const tickerTick = useMarketStore((s) => s.tickerTick)

  // seed 按 symbol 稳定(抖动兜底用,避免每次 tick 重算)
  const seed = useMemo(() => {
    let h = 0
    for (let i = 0; i < symbol.length; i++) h = (h * 31 + symbol.charCodeAt(i)) % 1000
    return h / 100
  }, [symbol])

  // 有 WS tick → 真实价(decimal.js);无 → base 快照 + sin 抖动兜底
  const hasTick = tick?.last != null
  const baseDec = toDecimal(hasTick ? tick!.last : base)
  let priceDec = baseDec
  let up: boolean
  if (hasTick) {
    // 24h 涨跌方向(percentage),跟 TickerCard pnl 一致;无 percentage 兜底涨
    up = toDecimal(tick!.percentage).gte(0)
  } else {
    const flick = Math.sin(tickerTick * 1.3 + seed) * 0.0012
    priceDec = baseDec.times(1 + flick)
    up = flick >= 0
  }

  const wrapRef = useRef<HTMLSpanElement | null>(null)
  const prevUp = useRef(up)

  useEffect(() => {
    if (prevUp.current !== up && wrapRef.current) {
      const el = wrapRef.current
      el.classList.remove('kq-flash')
      // reflow 触发动画重播
      void el.offsetWidth
      el.classList.add('kq-flash')
      prevUp.current = up
    }
  }, [up])

  return (
    <span
      ref={wrapRef}
      className={`kq-mono-row inline-block rounded-md px-1 py-px ${className ?? ''}`}
      style={{
        color: up ? 'var(--up)' : 'var(--down)',
        transition: 'color .2s',
      }}
    >
      {formatMoney(priceDec, { dp })}
    </span>
  )
}
