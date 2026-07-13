import { useEffect, useMemo, useRef } from 'react'
import { useMarketStore } from '@/stores/marketStore'

/**
 * LivePrice — 实时价格(强闪烁版)。
 *
 * 依赖全局 tickerTick(marketStore,1.8s 心跳),用 sin 抖动模拟价格跳动;
 * flick 方向翻转时给 DOM 加 .kq-flash class(0.8s 背景闪烁,index.css L274 已定义)。
 * 涨跌染色 var(--up)/var(--down)。
 *
 * 对齐原型 ui.jsx LivePrice(L203-222):prevUp 翻转检测 + classList add kq-flash + reflow 触发重播。
 * 真实 WS tick 接入后(阶段 4),tickerTick 由成交/报价驱动,闪烁更真实。
 */
function formatNum(v: number, dp: number): string {
  return v.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp })
}

export function LivePrice({
  symbol,
  base,
  dp = 2,
  className,
}: {
  symbol: string
  base: number
  dp?: number
  className?: string
}) {
  const tickerTick = useMarketStore((s) => s.tickerTick)
  // seed 按 symbol 稳定(避免每次 tick 重算),对齐原型 useMemo(Math.random, [symbol])
  const seed = useMemo(() => {
    // 简单字符串 hash 替代 Math.random(保持 symbol 稳定 + 不引入随机)
    let h = 0
    for (let i = 0; i < symbol.length; i++) h = (h * 31 + symbol.charCodeAt(i)) % 1000
    return h / 100
  }, [symbol])

  const flick = Math.sin(tickerTick * 1.3 + seed) * 0.0012
  const v = base * (1 + flick)
  const up = flick >= 0

  const wrapRef = useRef<HTMLSpanElement | null>(null)
  const prevUp = useRef(up)

  useEffect(() => {
    if (prevUp.current !== up && wrapRef.current) {
      const el = wrapRef.current
      el.classList.remove('kq-flash')
      // reflow 触发动画重播(对齐原型 void el.offsetWidth)
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
      {formatNum(v, dp)}
    </span>
  )
}
