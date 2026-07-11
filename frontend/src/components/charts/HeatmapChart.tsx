/**
 * HeatmapChart — 热力矩阵(裸 SVG port)。
 *
 * 照原型 ui.jsx Heatmap(L288-324) port,变量映射(hair→border-soft / ink-3→text-muted /
 * ink-2→text-secondary / ink→text-primary)。
 * 视觉:色块矩阵(up/down 染色 + fillOpacity 按 abs/maxAbs 0.12→0.9)+ 行均值列 +
 * 行/列标签 + kq-mono-row 单元格数字。
 * 用于板块涨跌热度展示。
 */
export function HeatmapChart({
  data,
  rowLabels,
  colLabels,
  cellW = 64,
  cellH = 34,
  gap = 3,
  fmtVal,
}: {
  data: number[][]
  rowLabels: string[]
  colLabels: string[]
  cellW?: number
  cellH?: number
  gap?: number
  fmtVal?: (v: number) => string
}) {
  const rows = data.length
  const cols = data[0].length
  const maxAbs = Math.max(...data.flat().map((v) => Math.abs(v))) || 1
  const rowAvgs = data.map((r) => r.reduce((a, b) => a + b, 0) / cols)
  const xFor = (c: number) => gap + c * (cellW + gap)
  const yFor = (r: number) => gap + r * (cellH + gap)
  const labelW = cellW * 0.6
  const rowLabelX = gap
  const colLabelY = gap
  const W = cols * (cellW + gap) + gap + labelW + gap
  const H = rows * (cellH + gap) + gap + 16
  const fv = fmtVal || ((v: number) => (v >= 0 ? '+' : '') + v.toFixed(1) + '%')
  const cellText = (_v: number, i: number) => (i >= 0.55 ? '#fff' : 'var(--text-primary)')

  return (
    <svg width={W} height={H} style={{ display: 'block', fontFamily: 'inherit' }}>
      {colLabels.map((lab, c) => (
        <text
          key={'ch-' + c}
          x={xFor(c) + cellW / 2 + labelW + gap}
          y={colLabelY + 9}
          textAnchor="middle"
          fontSize="9.5"
          fill="var(--text-muted)"
          fontWeight="600"
          letterSpacing=".04em"
        >
          {lab}
        </text>
      ))}
      {data.map((row, r) =>
        row.map((v, c) => {
          const i = Math.min(0.88, (Math.abs(v) / maxAbs) * 0.88)
          const up = v >= 0
          const x = xFor(c) + labelW + gap
          const y = yFor(r)
          return (
            <g key={r + '-' + c}>
              <rect
                x={x}
                y={y}
                width={cellW}
                height={cellH}
                rx="4"
                fill={up ? 'var(--up)' : 'var(--down)'}
                fillOpacity={0.12 + i * 0.78}
                stroke="var(--border-soft)"
                strokeWidth="0.5"
              />
              <text
                x={x + cellW / 2}
                y={y + cellH / 2 + 3.5}
                textAnchor="middle"
                fontSize="11"
                fontFamily="ui-monospace,monospace"
                fill={cellText(v, i)}
                fontWeight="600"
              >
                {fv(v)}
              </text>
            </g>
          )
        }),
      )}
      {rowLabels.map((lab, r) => (
        <text
          key={'rl-' + r}
          x={rowLabelX}
          y={yFor(r) + cellH / 2 + 3.5}
          fontSize="11.5"
          fill="var(--text-secondary)"
          fontWeight="600"
        >
          {lab}
        </text>
      ))}
      {rowAvgs.map((v, r) => {
        const i = Math.min(0.88, (Math.abs(v) / maxAbs) * 0.88)
        const up = v >= 0
        const x = xFor(cols) + labelW + gap
        const y = yFor(r)
        return (
          <g key={'ra-' + r}>
            <rect
              x={x}
              y={y}
              width={labelW}
              height={cellH}
              rx="4"
              fill={up ? 'var(--up)' : 'var(--down)'}
              fillOpacity={0.12 + i * 0.78}
              stroke="var(--border-soft)"
              strokeWidth="0.5"
            />
            <text
              x={x + labelW / 2}
              y={y + cellH / 2 + 3.5}
              textAnchor="middle"
              fontSize="10"
              fontFamily="ui-monospace,monospace"
              fill={cellText(v, i)}
              fontWeight="600"
            >
              {fv(v)}
            </text>
          </g>
        )
      })}
    </svg>
  )
}
