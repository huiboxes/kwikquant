import { useId } from 'react'

/**
 * EquityCurveChart — 组合权益曲线(裸 SVG port)。
 *
 * 照原型 ui.jsx EquityCurve(L97-135) 逐行 port,变量映射(hair→border-soft / ink-3→text-muted / surface→surface-card)。
 * 视觉:渐变面积(0.32→0)+ feGaussianBlur glow filter + 末端脉冲圆点(<animate>)+
 * Y 轴虚线网格 + kq-mono-row 刻度。
 *
 * 用 useId() 替代原型 Math.random() 生成 gradient id(稳定,不随渲染变)。
 * data 格式 [x, y][] 对齐原型;阶段 7 接 EquityPointDto 时在调用方转换。
 */
export function EquityCurveChart({
  data,
  width = 740,
  height = 220,
  color = 'var(--up)',
  showArea = true,
}: {
  data: Array<[number, number]>
  width?: number
  height?: number
  color?: string
  showArea?: boolean
}) {
  const rawId = useId()
  const gid = 'eq' + rawId.replace(/:/g, '')
  const glowId = gid + 'g'

  // 空/单点 data 早返回(工程防御:dev 无端点 or 后端无数据时不崩,展示占位)。
  // 否则 data[0] / data[data.length-1] 访问 undefined → "Cannot read properties of undefined (reading '0')"。
  if (!data || data.length < 2) {
    return (
      <svg width={width} height={height} style={{ display: 'block' }}>
        <text
          x={width / 2}
          y={height / 2}
          textAnchor="middle"
          dominantBaseline="middle"
          fill="var(--text-muted)"
          fontSize="10"
          className="kq-mono-row"
        >
          暂无数据
        </text>
      </svg>
    )
  }

  const W = width
  const H = height
  const min = Math.min(...data.map((d) => d[1]))
  const max = Math.max(...data.map((d) => d[1]))
  const padL = 44
  const padR = 14
  const padT = 14
  const padB = 22
  const xs = (d: [number, number]) => padL + (d[0] / (data.length - 1)) * (W - padL - padR)
  const ys = (v: number) => padT + (1 - (v - min) / (max - min || 1)) * (H - padT - padB)
  const line = data
    .map((d, i) => `${i === 0 ? 'M' : 'L'} ${xs(d).toFixed(1)} ${ys(d[1]).toFixed(1)}`)
    .join(' ')
  const area = `${line} L ${xs(data[data.length - 1]).toFixed(1)} ${H - padB} L ${xs(data[0]).toFixed(1)} ${H - padB} Z`
  const gridYs = [0, 0.25, 0.5, 0.75, 1].map((p) => padT + p * (H - padT - padB))
  const fmtShort = (n: number) =>
    Math.abs(n) >= 1000 ? (n / 1000).toFixed(1) + 'k' : n.toFixed(0)
  const lastX = xs(data[data.length - 1])
  const lastY = ys(data[data.length - 1][1])

  return (
    <svg width={W} height={H} style={{ display: 'block' }}>
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.32" />
          <stop offset="60%" stopColor={color} stopOpacity="0.08" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
        <filter id={glowId} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3" result="b" />
          <feMerge>
            <feMergeNode in="b" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>
      {gridYs.map((y, i) => (
        <g key={i}>
          <line
            x1={padL}
            x2={W - padR}
            y1={y}
            y2={y}
            stroke="var(--border-soft)"
            strokeDasharray="2 4"
            strokeWidth="1"
            opacity={i === 0 || i === gridYs.length - 1 ? 1 : 0.6}
          />
          <text
            x={padL - 8}
            y={y + 3}
            fontSize="9"
            fill="var(--text-muted)"
            textAnchor="end"
            className="kq-mono-row"
          >
            {fmtShort(i === 0 ? max : i === gridYs.length - 1 ? min : min + (max - min) * (1 - i / 4))}
          </text>
        </g>
      ))}
      {showArea !== false && <path d={area} fill={`url(#${gid})`} stroke="none" />}
      <path
        d={line}
        fill="none"
        stroke={color}
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        filter={`url(#${glowId})`}
      />
      <circle cx={lastX} cy={lastY} r="4" fill={color} opacity="0.25">
        <animate attributeName="r" values="4;9;4" dur="2s" repeatCount="indefinite" />
        <animate attributeName="opacity" values="0.35;0;0.35" dur="2s" repeatCount="indefinite" />
      </circle>
      <circle cx={lastX} cy={lastY} r="3.5" fill={color} stroke="var(--surface-card)" strokeWidth="1.5" />
    </svg>
  )
}
