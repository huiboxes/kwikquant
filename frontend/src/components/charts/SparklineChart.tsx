import { useId } from 'react'

/**
 * SparklineChart — 迷你火花线(裸 SVG port)。
 *
 * 照原型 ui.jsx Sparkline(L170-193) port,变量映射同 EquityCurve。
 * 视觉:渐变面积(0.28→0)+ 主线 + 末端脉冲圆点(<animate opacity>)。
 * 涨跌方向(末值 vs 首值)决定默认色。用于策略行/账户卡的迷你趋势。
 */
export function SparklineChart({
  data,
  width = 80,
  height = 24,
  color,
}: {
  data: number[]
  width?: number
  height?: number
  color?: string
}) {
  const rawId = useId()
  const gid = 'sp' + rawId.replace(/:/g, '')

  // 空/单点 data 早返回(工程防御:避免 data[0]/data[length-1] 访问 undefined 崩)。
  if (!data || data.length < 2) {
    return <svg width={width} height={height} style={{ display: 'block' }} />
  }

  const W = width
  const H = height
  const min = Math.min(...data)
  const max = Math.max(...data)
  const xs = (i: number) => (i / (data.length - 1)) * W
  const ys = (v: number) => (1 - (v - min) / (max - min || 1)) * H
  const line = data
    .map((v, i) => `${i === 0 ? 'M' : 'L'} ${xs(i).toFixed(1)} ${ys(v).toFixed(1)}`)
    .join(' ')
  const c = color || (data[data.length - 1] >= data[0] ? 'var(--up)' : 'var(--down)')
  const area = `${line} L ${W} ${H} L 0 ${H} Z`

  return (
    <svg width={W} height={H} style={{ display: 'block', overflow: 'visible' }}>
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={c} stopOpacity="0.28" />
          <stop offset="100%" stopColor={c} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${gid})`} stroke="none" />
      <path
        d={line}
        fill="none"
        stroke={c}
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={xs(data.length - 1)} cy={ys(data[data.length - 1])} r="2" fill={c}>
        <animate attributeName="opacity" values="1;0.4;1" dur="1.8s" repeatCount="indefinite" />
      </circle>
    </svg>
  )
}
