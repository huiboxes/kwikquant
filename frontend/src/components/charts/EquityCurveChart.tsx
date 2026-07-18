import { useId } from 'react'

type EquitySeries = {
  data: Array<[number, number]>
  color?: string
  label?: string
}

/**
 * EquityCurveChart — 组合权益曲线(裸 SVG port)。
 *
 * 照原型 ui.jsx EquityCurve(L97-135) 逐行 port,变量映射(hair→border-soft / ink-3→text-muted / surface→surface-card)。
 * 视觉:渐变面积(0.32→0)+ feGaussianBlur glow filter + 末端脉冲圆点(<animate>)+
 * Y 轴虚线网格 + kq-mono-row 刻度。
 *
 * 用 useId() 替代原型 Math.random() 生成 gradient id(稳定,不随渲染变)。
 * data 格式 [x, y][] 对齐原型;阶段 7 接 EquityPointDto 时在调用方转换。
 *
 * 多曲线(TD-018 对比叠图):series[] 叠图共享 Y scale(所有点 min/max),
 * x 归一化(各 curve index/(len-1) 映射 padL..W-padR)对齐起止(不同回测 trades 数不同)。
 * 多曲线(≥2)只 line + 图例(不 area 避免半透明叠乱);单曲线保持 area + glow + 末端脉冲。
 */
export function EquityCurveChart({
  data,
  series,
  width = 740,
  height = 220,
  color = 'var(--up)',
  showArea = true,
}: {
  data?: Array<[number, number]>
  series?: EquitySeries[]
  width?: number
  height?: number
  color?: string
  showArea?: boolean
}) {
  const rawId = useId()
  const gid = 'eq' + rawId.replace(/:/g, '')
  const glowId = gid + 'g'

  // 归一化:series 优先,data 兼容单曲线
  const allSeries: EquitySeries[] =
    series ?? (data ? [{ data, color: color ?? 'var(--up)' }] : [])

  // 空/单点 data 早返回(工程防御:dev 无端点 or 后端无数据时不崩,展示占位)。
  if (allSeries.length === 0 || allSeries.every((s) => s.data.length < 2)) {
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
  const padL = 44
  const padR = 14
  const padT = 14
  const padB = 22
  // 共享 Y scale:所有 series 所有点 min/max(叠图对齐纵轴)
  const allValues = allSeries.flatMap((s) => s.data.map((d) => d[1]))
  const min = Math.min(...allValues)
  const max = Math.max(...allValues)
  // x 归一化:各 curve 自己 index/(len-1) → padL..W-padR(对齐起止,容忍不等长)
  const xs = (i: number, len: number) => padL + (i / (len - 1 || 1)) * (W - padL - padR)
  const ys = (v: number) => padT + (1 - (v - min) / (max - min || 1)) * (H - padT - padB)
  const gridYs = [0, 0.25, 0.5, 0.75, 1].map((p) => padT + p * (H - padT - padB))
  const fmtShort = (n: number) =>
    Math.abs(n) >= 1000 ? (n / 1000).toFixed(1) + 'k' : n.toFixed(0)

  const isMulti = allSeries.length >= 2

  const lineOf = (s: EquitySeries) =>
    s.data
      .map((d, i) => `${i === 0 ? 'M' : 'L'} ${xs(i, s.data.length).toFixed(1)} ${ys(d[1]).toFixed(1)}`)
      .join(' ')
  const areaOf = (s: EquitySeries) =>
    `${lineOf(s)} L ${xs(s.data.length - 1, s.data.length).toFixed(1)} ${H - padB} L ${xs(0, s.data.length).toFixed(1)} ${H - padB} Z`

  const s0 = allSeries[0]!
  const lastX = xs(s0.data.length - 1, s0.data.length)
  const lastY = ys(s0.data[s0.data.length - 1]![1])

  return (
    <svg width={W} height={H} style={{ display: 'block' }}>
      <defs>
        {/* area gradient + glow filter 只单曲线用(多曲线不 area 避免半透明叠乱) */}
        {!isMulti && (
          <>
            <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={s0.color ?? color} stopOpacity="0.32" />
              <stop offset="60%" stopColor={s0.color ?? color} stopOpacity="0.08" />
              <stop offset="100%" stopColor={s0.color ?? color} stopOpacity="0" />
            </linearGradient>
            <filter id={glowId} x="-50%" y="-50%" width="200%" height="200%">
              <feGaussianBlur stdDeviation="3" result="b" />
              <feMerge>
                <feMergeNode in="b" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
          </>
        )}
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
      {allSeries.map((s, idx) => {
        const c = s.color ?? color
        return (
          <g key={idx}>
            {!isMulti && showArea !== false && <path d={areaOf(s)} fill={`url(#${gid})`} stroke="none" />}
            <path
              d={lineOf(s)}
              fill="none"
              stroke={c}
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              filter={!isMulti ? `url(#${glowId})` : undefined}
            />
          </g>
        )
      })}
      {/* 末端脉冲只单曲线 */}
      {!isMulti && (
        <>
          <circle cx={lastX} cy={lastY} r="4" fill={s0.color ?? color} opacity="0.25">
            <animate attributeName="r" values="4;9;4" dur="2s" repeatCount="indefinite" />
            <animate attributeName="opacity" values="0.35;0;0.35" dur="2s" repeatCount="indefinite" />
          </circle>
          <circle cx={lastX} cy={lastY} r="3.5" fill={s0.color ?? color} stroke="var(--surface-card)" strokeWidth="1.5" />
        </>
      )}
      {/* 多曲线图例(顶部) */}
      {isMulti && (
        <g>
          {allSeries.map((s, idx) => {
            const lx = padL + idx * 110
            return (
              <g key={idx}>
                <rect x={lx} y={3} width={12} height={4} fill={s.color ?? color} rx="1" />
                <text x={lx + 16} y={9} fontSize="9" fill="var(--text-muted)" className="kq-mono-row">
                  {s.label ?? `#${idx + 1}`}
                </text>
              </g>
            )
          })}
        </g>
      )}
    </svg>
  )
}
