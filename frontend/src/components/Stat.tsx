import { useEffect, useRef, useState } from 'react'
import { toDecimal } from '@/lib/money'

/**
 * Stat — 统计指标卡(带 RAF 数字 tween 动画)。
 *
 * 接收展示 value(string，如 "+58.4%" / "2.31" / "184 笔")，解析数字部分做 0→target
 * tween(600ms cubic ease-out)，非数字直接展示。tone 染色(up/down/accent)。
 *
 * 金额红线：解析数字用 toDecimal(m).toNumber() 而非 parseFloat/Number(后者被 ESLint 硬拦)。
 * tween 中间值是动画进度 number 运算，非金额运算；展示用 number.toLocaleString 格式化。
 *
 * 对齐原型 ui.jsx Stat(L224-256):正则提取 sign+number+suffix,tween number，拼回展示。
 */
const TONE_COLOR: Record<string, string> = {
  up: 'var(--up)',
  down: 'var(--down)',
  accent: 'var(--accent)',
}

export function Stat({
  label,
  value,
  sub,
  tone,
  mono,
  className,
}: {
  label: string
  value: string | null
  sub?: string
  tone?: 'up' | 'down' | 'accent'
  mono?: boolean
  className?: string
}) {
  const raw = value == null ? '' : String(value)
  // 正则提取符号 + 数字(含千分位逗号/小数)+ 后缀
  const m = raw.match(/^([-+]?)([\d,]*\.?\d+)(.*)$/)
  const canCount = m != null && /[0-9]/.test(raw)
  const targetNum = canCount ? toDecimal(m![2].replace(/,/g, '')).toNumber() : 0
  const prefix = canCount ? m![1] : ''
  const suffix = canCount ? m![3] : ''
  const dp = canCount ? (m![2].split('.')[1] ?? '').length : 0

  // reduced-motion:终值派生直出,不进 tween state(也避开 effect 内同步 setState)
  const prefersReduced =
    typeof window !== 'undefined' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const [animated, setAnimated] = useState(0)
  const rafRef = useRef<number | null>(null)

  useEffect(() => {
    if (!canCount || prefersReduced) return
    const dur = 600
    const t0 = performance.now()
    const tick = (now: number) => {
      const p = Math.min(1, (now - t0) / dur)
      const e = 1 - Math.pow(1 - p, 3) // cubic ease-out
      setAnimated(targetNum * e)
      if (p < 1) rafRef.current = requestAnimationFrame(tick)
    }
    rafRef.current = requestAnimationFrame(tick)
    return () => {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetNum, prefersReduced])

  const shown = canCount ? (prefersReduced ? targetNum : animated) : 0
  const display = canCount
    ? prefix +
      shown.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp }) +
      suffix
    : raw

  return (
    <div className={className}>
      <div className="text-label-caps uppercase text-text-muted">{label}</div>
      <div
        className={`text-metric font-semibold leading-none ${mono ? 'kq-mono-row' : ''}`}
        style={tone ? { color: TONE_COLOR[tone] } : undefined}
      >
        {display}
      </div>
      {sub && <div className="mt-0.5 text-caption text-text-muted">{sub}</div>}
    </div>
  )
}
