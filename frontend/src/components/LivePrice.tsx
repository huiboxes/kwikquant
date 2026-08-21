import { useEffect, useRef } from 'react'
import { useMarketStore } from '@/stores/marketStore'
import { toDecimal, formatMoney } from '@/lib/money'

/**
 * LivePrice — 实时价格(WS tick 驱动，无 tick 静态降级)。
 *
 * 优先读 marketStore.ticks[symbol].last(WS 实时 tick,BigDecimal string → decimal.js);
 * 无 tick(WS 未连/未推送)时静态显示 base(REST 快照)中性色——不再用 sin 伪造价格抖动
 * (走查 C5:假跳动是欺骗性降级，用户会误以为有实时行情)。
 * 翻转方向(涨/跌)时 DOM 加 .kq-flash class(0.8s 背景闪烁，index.css 已定义)。
 *
 * 金额红线：价格走 toDecimal + formatMoney(decimal.js)，不直接 number 运算。后端 BigDecimal 实际序列化为
 * JSON number(Jackson 默认，无 @JsonFormat/全局配)，非 string — 金额红线缺口(精度 >2^53 丢)，长期 TD
 * 后端 @JsonFormat(shape=STRING) 或全局 Jackson 配 BigDecimal→string，届时无需改本组件(toDecimal 兼容)。
 */
export function LivePrice({
  exchange,
  marketType,
  symbol,
  base,
  dp = 2,
  className,
}: {
  exchange: string
  marketType: string
  symbol: string
  /** REST 快照价(BigDecimal string;WS tick 来了优先用 tick.last)。 */
  base: string
  dp?: number
  className?: string
}) {
  // ticks key 三元组(exchange:marketType:symbol)防 SPOT/PERP 同 symbol 覆盖
  const tick = useMarketStore((s) => s.ticks[`${exchange}:${marketType}:${symbol}`])

  // 有 WS tick → 真实价(decimal.js)+涨跌色；无 tick → base 快照静态显示(中性色，无假抖动)
  const hasTick = tick?.last != null
  const priceDec = toDecimal(hasTick ? tick!.last : base)
  const up: boolean | null = hasTick ? toDecimal(tick!.percentage).gte(0) : null

  const wrapRef = useRef<HTMLSpanElement | null>(null)
  const prevUp = useRef<boolean | null>(up)

  useEffect(() => {
    if (up != null && prevUp.current !== up && wrapRef.current) {
      const el = wrapRef.current
      el.classList.remove('kq-flash')
      // reflow 触发动画重播
      void el.offsetWidth
      el.classList.add('kq-flash')
    }
    prevUp.current = up
  }, [up])

  return (
    <span
      ref={wrapRef}
      className={`kq-mono-row inline-block rounded-md px-1 py-px ${className ?? ''}`}
      style={{
        color: up == null ? 'var(--text-secondary)' : up ? 'var(--up)' : 'var(--down)',
        transition: 'color .2s',
      }}
      title={up == null ? '实时行情暂不可用，显示最近快照价' : undefined}
    >
      {formatMoney(priceDec, { dp })}
    </span>
  )
}
