import { useEffect, useRef } from 'react'
import { createChart, AreaSeries } from 'lightweight-charts'
import { mapEquityCurve } from '@/lib/equityMap'
import type { components } from '@/types/api-gen'

type EquityPointDto = components['schemas']['EquityPointDto']

/**
 * hex → rgba 字符串。
 * lightweight-charts AreaSeries topColor/bottomColor 需 rgba(带 alpha),不支持 CSS 变量直接传入。
 * CSS 变量缺失(异常/fallback)时用 DESIGN.md accent #C2410C,与 --color-accent 同值,非随意色。
 */
function hexToRgba(hex: string, alpha: number): string {
  if (!hex.startsWith('#')) return `rgba(194, 65, 12, ${alpha})`
  const h = hex.replace('#', '')
  const r = parseInt(h.slice(0, 2), 16)
  const g = parseInt(h.slice(2, 4), 16)
  const b = parseInt(h.slice(4, 6), 16)
  if (Number.isNaN(r) || Number.isNaN(g) || Number.isNaN(b))
    return `rgba(194, 65, 12, ${alpha})`
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/**
 * EquityChart — 权益曲线(spec §4.4 面积图)。
 *
 * lightweight-charts v5 AreaSeries(渐变填充,替 LineSeries;不引 Recharts,DESIGN.md §10.4 禁)。
 * EquityPointDto[] → mapEquityCurve → {time:UTCTimestamp, value:number}。
 *
 * 颜色读 CSS 变量(--color-accent):lineColor 用 hex,topColor/bottomColor 用 hexToRgba 转带 alpha 的 rgba。
 * ResizeObserver 响应容器宽度;MutationObserver 监听 html.dark 切换重读颜色。
 * unmount 时 chart.remove() 释放资源。
 */
export interface EquityChartProps {
  equityCurve: EquityPointDto[]
}

export function EquityChart({ equityCurve }: EquityChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    const cs = getComputedStyle(el)
    // 读 DESIGN.md token 值;空串 fallback 为 transparent(避免硬编码 hex)
    const accent = cs.getPropertyValue('--color-accent').trim()
    const textColor = cs.getPropertyValue('--color-text-secondary').trim()
    const borderSoft = cs.getPropertyValue('--color-border-soft').trim()
    const border = cs.getPropertyValue('--color-border').trim()
    const canvas = cs.getPropertyValue('--color-surface-card').trim()

    const chart = createChart(el, {
      width: el.clientWidth,
      height: 320,
      layout: {
        background: { color: canvas || 'transparent' },
        textColor: textColor || undefined,
      },
      grid: {
        vertLines: { color: borderSoft || undefined },
        horzLines: { color: borderSoft || undefined },
      },
      timeScale: {
        timeVisible: true,
        secondsVisible: false,
      },
      rightPriceScale: {
        borderColor: border || undefined,
      },
    })

    const series = chart.addSeries(AreaSeries, {
      lineColor: accent || undefined,
      topColor: hexToRgba(accent, 0.4),
      bottomColor: hexToRgba(accent, 0),
      lineWidth: 2,
    })
    series.setData(mapEquityCurve(equityCurve))
    chart.timeScale().fitContent()

    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width
      if (w) chart.applyOptions({ width: w })
    })
    ro.observe(el)

    // 主题切换(html .dark class 变化)时重读 CSS 变量颜色,避免暗→亮后 chart 色不更新
    const applyTheme = () => {
      const cs2 = getComputedStyle(el)
      const accent2 = cs2.getPropertyValue('--color-accent').trim()
      chart.applyOptions({
        layout: {
          background: {
            color: cs2.getPropertyValue('--color-surface-card').trim() || 'transparent',
          },
          textColor: cs2.getPropertyValue('--color-text-secondary').trim() || undefined,
        },
        grid: {
          vertLines: { color: cs2.getPropertyValue('--color-border-soft').trim() || undefined },
          horzLines: { color: cs2.getPropertyValue('--color-border-soft').trim() || undefined },
        },
        rightPriceScale: {
          borderColor: cs2.getPropertyValue('--color-border').trim() || undefined,
        },
      })
      series.applyOptions({
        lineColor: accent2 || undefined,
        topColor: hexToRgba(accent2, 0.4),
        bottomColor: hexToRgba(accent2, 0),
      })
    }
    const mo = new MutationObserver(applyTheme)
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

    return () => {
      mo.disconnect()
      ro.disconnect()
      chart.remove()
    }
  }, [equityCurve])

  return (
    <div
      ref={containerRef}
      className="w-full"
      style={{ height: 320 }}
      role="img"
      aria-label="权益曲线"
    />
  )
}
