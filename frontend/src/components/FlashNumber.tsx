import { useEffect, useRef, useState } from 'react'
import { toDecimal } from '@/lib/money'

/**
 * FlashNumber — 数字变化的方向性反馈(mono + 800ms 涨/跌着色 + 背景闪)。
 *
 * 交易工作台的"钱在动"时刻:余额/总资产/盈亏跳变时给用户方向感。
 * 首帧与等值更新不闪;非数字值(如 "--")静默展示。
 * 金额比较走 decimal.js(红线),千分位逗号先剥离。
 */
export function FlashNumber({ value, className }: { value: string; className?: string }) {
  const [dir, setDir] = useState<'up' | 'down' | null>(null)
  const prev = useRef<string | null>(null)

  useEffect(() => {
    const before = prev.current
    prev.current = value
    if (before == null || before === value) return
    const a = parseNum(before)
    const b = parseNum(value)
    if (a == null || b == null || a === b) return
    setDir(b > a ? 'up' : 'down')
  }, [value])

  useEffect(() => {
    if (dir == null) return
    const t = window.setTimeout(() => setDir(null), 800)
    return () => window.clearTimeout(t)
  }, [dir, value])

  const tick = dir === 'up' ? 'kq-tick-up kq-flash' : dir === 'down' ? 'kq-tick-down kq-flash' : ''
  return <span className={`kq-mono-row ${tick} ${className ?? ''}`.trim()}>{value}</span>
}

/** 展示串 → number(剥离千分位);非数字返 null,不抛错。 */
function parseNum(v: string): number | null {
  const stripped = v.replace(/,/g, '')
  if (!/^-?\d+(\.\d+)?$/.test(stripped)) return null
  return toDecimal(stripped).toNumber()
}
