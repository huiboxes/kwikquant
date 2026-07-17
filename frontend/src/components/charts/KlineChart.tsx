import { useEffect, useRef } from 'react'
import {
  createChart,
  CandlestickSeries,
  HistogramSeries,
  type IChartApi,
  type ISeriesApi,
  type Time,
} from 'lightweight-charts'
import { toUnixSeconds } from '@/lib/toUnixSeconds'

/**
 * KlineChart — K 线图(lightweight-charts v5)。
 *
 * 对齐原型 ui.jsx Candles(L137-168) 视觉:up/down 染色 + 影线实体 + VOL 副图(74%/26%)+
 * 虚线网格。lightweight-charts 专业级(缩放/十字光标),优于裸 SVG。
 *
 * token 问题:lightweight-charts 不解析 CSS 变量,用 getComputedStyle 读 --up/--down/--border-soft/--text-muted,
 * 不硬编码色值(符合 DESIGN.md)。themeStore 切换时 reapply(下方 effect 依赖 colorScheme)。
 * autoSize:true 让 v5 用 ResizeObserver 自适应容器宽度(对齐原型 MarketPage ResizeObserver 模式)。
 */
export interface KlineCandle {
  ts: string // ISO-8601
  o: number
  h: number
  l: number
  c: number
  v?: number
}

function cssVar(name: string): string {
  if (typeof window === 'undefined') return ''
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

export function KlineChart({
  data,
  updateCandle,
  height = 260,
  className,
}: {
  data: KlineCandle[]
  /** WS 最新 candle(增量 update,同 time replace 最后/新 time append,保留缩放/十字光标)。 */
  updateCandle?: KlineCandle
  height?: number
  className?: string
}) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null)

  // 创建 chart + series(仅 height 变化时重建)
  useEffect(() => {
    if (!containerRef.current) return
    const up = cssVar('--up')
    const down = cssVar('--down')
    const border = cssVar('--border-soft')
    const text = cssVar('--text-muted')

    const chart = createChart(containerRef.current, {
      height,
      autoSize: true,
      layout: {
        background: { color: 'transparent' },
        textColor: text,
        fontFamily: 'ui-monospace, monospace',
      },
      grid: {
        vertLines: { color: border, style: 2 },
        horzLines: { color: border, style: 2 },
      },
      rightPriceScale: { borderColor: border },
      timeScale: { borderColor: border, rightOffset: 4 },
      crosshair: { mode: 1 },
    })
    chartRef.current = chart

    const candle = chart.addSeries(CandlestickSeries, {
      upColor: up,
      downColor: down,
      borderUpColor: up,
      borderDownColor: down,
      wickUpColor: up,
      wickDownColor: down,
    })
    candleRef.current = candle

    const vol = chart.addSeries(HistogramSeries, {
      priceFormat: { type: 'volume' },
      priceScaleId: 'vol',
    })
    chart.priceScale('vol').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })
    volRef.current = vol

    return () => {
      chart.remove()
      chartRef.current = null
      candleRef.current = null
      volRef.current = null
    }
  }, [height])

  // 数据更新
  useEffect(() => {
    if (!candleRef.current || !volRef.current) return
    candleRef.current.setData(
      data.map((d) => ({
        time: toUnixSeconds(d.ts) as Time,
        open: d.o,
        high: d.h,
        low: d.l,
        close: d.c,
      })),
    )
    volRef.current.setData(
      data.map((d) => ({
        time: toUnixSeconds(d.ts) as Time,
        value: d.v ?? 0,
        color: d.c >= d.o ? cssVar('--up') : cssVar('--down'),
      })),
    )
  }, [data])

  // 增量更新(WS 最新 candle):update() 同 time replace 最后 / 新 time append,保留缩放/十字光标(不像 setData 全量重渲染)。
  useEffect(() => {
    if (!candleRef.current || !volRef.current || !updateCandle) return
    const t = toUnixSeconds(updateCandle.ts) as Time
    candleRef.current.update({
      time: t,
      open: updateCandle.o,
      high: updateCandle.h,
      low: updateCandle.l,
      close: updateCandle.c,
    })
    volRef.current.update({
      time: t,
      value: updateCandle.v ?? 0,
      color: updateCandle.c >= updateCandle.o ? cssVar('--up') : cssVar('--down'),
    })
  }, [updateCandle])

  return <div ref={containerRef} className={className} style={{ height, width: '100%' }} />
}
