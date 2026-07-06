import { useEffect, useRef } from 'react'
import { createChart, LineSeries } from 'lightweight-charts'
import { mapEquityCurve } from '@/lib/equityMap'
import type { components } from '@/types/api-gen'

type EquityPointDto = components['schemas']['EquityPointDto']

/**
 * EquityChart — 权益曲线(spec §5 step 21)。
 *
 * lightweight-charts v5 line series(不引 Recharts,DESIGN.md §10.4 禁卡片内嵌 Recharts)。
 * EquityPointDto[] → mapEquityCurve → {time:UTCTimestamp, value:number}。
 *
 * 颜色读 CSS 变量(--color-accent 等),不硬编码(DESIGN.md token 流转)。
 * ResizeObserver 响应容器宽度变化。
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

    const series = chart.addSeries(LineSeries, {
      color: accent || undefined,
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
        color: cs2.getPropertyValue('--color-accent').trim() || undefined,
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
