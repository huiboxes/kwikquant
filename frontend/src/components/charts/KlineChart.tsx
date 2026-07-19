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
  onLoadMore,
  loadingMore,
  noMore,
  height = 260,
  className,
}: {
  data: KlineCandle[]
  /** WS 最新 candle(增量 update,同 time replace 最后/新 time append,保留缩放/十字光标)。 */
  updateCandle?: KlineCandle
  /** 往前滚加载历史:用户滚到最左时触发,拉更早 K线 prepend(生产级)。 */
  onLoadMore?: () => void
  /** 正在加载更多(防 subscribe 重复触发)。 */
  loadingMore?: boolean
  /** 无更多历史(数据耗尽,KlineChart 不再触发 onLoadMore,防 fetchKlines 空死循环)。 */
  noMore?: boolean
  height?: number
  className?: string
}) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null)
  // series 最后 time(setData 后设)。update 前用它校验:series 空(快速切 interval data 短暂空)或
  // time 倒退(旧 candle 残留)时 skip,避免 lightweight-charts "Cannot update oldest data"。
  const lastTimeRef = useRef<number | undefined>(undefined)
  // ref 持有 onLoadMore/loadingMore/noMore 最新值,避免 subscribeVisibleLogicalRangeChange 闭包 stale
  // (subscribe 在 createChart effect [height] 注册,不随 props 变重注册)。
  const onLoadMoreRef = useRef(onLoadMore)
  const loadingMoreRef = useRef(loadingMore)
  const noMoreRef = useRef(noMore)
  // armed:用户手势滚动(wheel/pointerdown)后才 true,跳过程序 setData/首屏 fire 防 auto-trigger 死循环(H1)。
  const armedRef = useRef(false)
  useEffect(() => {
    onLoadMoreRef.current = onLoadMore
    loadingMoreRef.current = loadingMore
    noMoreRef.current = noMore
  }, [onLoadMore, loadingMore, noMore])

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
        attributionLogo: false,
      },
      grid: {
        vertLines: { color: border, style: 2 },
        horzLines: { color: border, style: 2 },
      },
      rightPriceScale: { borderColor: border },
      timeScale: {
        borderColor: border,
        rightOffset: 4,
        timeVisible: true, // 按 interval 显示时间(1h 显示 MM-DD HH:mm,1d 显示 MM-DD)
        secondsVisible: false, // 不显示秒(K 线最小粒度 1m,秒噪声)
      },
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
    // 蜡烛占主区(顶 10% 透气 + 底 20% 让给量柱),避免与 VOL 副图重叠(BUG 修复)。
    chart.priceScale('right').applyOptions({
      scaleMargins: { top: 0.1, bottom: 0.2 },
    })

    const vol = chart.addSeries(HistogramSeries, {
      priceFormat: { type: 'volume' },
      priceScaleId: 'vol',
    })
    // 量柱占底部 20%(top:0.8 起),与蜡烛主区(0.1–0.8)分界不重叠。
    chart.priceScale('vol').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })
    volRef.current = vol

    // 往前滚加载历史:用户手势滚动(armed)接近左侧(from < 10,预加载提前量)且非 loading/noMore → onLoadMore。
    // armed 守卫:只在用户 wheel/pointerdown 后触发,跳过程序 setData/首屏 fire(H1 防 auto-trigger 死循环)。
    // noMore:数据耗尽(older 空)后不再触发,防 fetchKlines 空死循环。
    // from<10(非 <3):缩小图表/快拉时可见区左移更早触发,避免左侧空一大段(BUG 修复)。
    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!armedRef.current || !range) return
      if (range.from < 10 && !loadingMoreRef.current && !noMoreRef.current) {
        onLoadMoreRef.current?.()
      }
    })
    // 用户手势(wheel/pointerdown)才 arm — 程序 setData/首屏布局的 fire 不 arm,不触发 onLoadMore。
    const arm = () => {
      armedRef.current = true
    }
    const el = containerRef.current
    el?.addEventListener('wheel', arm, { capture: true })
    el?.addEventListener('pointerdown', arm, { capture: true })

    return () => {
      el?.removeEventListener('wheel', arm, { capture: true })
      el?.removeEventListener('pointerdown', arm, { capture: true })
      chart.remove()
      chartRef.current = null
      candleRef.current = null
      volRef.current = null
      lastTimeRef.current = undefined // chart 重建([height])时重置,防 WS update 到新空 series 抛 oldest data(L1)
      armedRef.current = false // 新 chart 用户未滚过,重新 arm
    }
  }, [height])

  // 数据更新
  useEffect(() => {
    if (!candleRef.current || !volRef.current) return
    // lightweight-charts v5 要求 setData 严格按 time 升序;后端 findRecent 返 DESC(最近 N 根最新在前),
    // 这里按 time 升序排序后喂图表(消费适配,非后端 bug — findRecent DESC 是"最近 N 根"的合理语义)。
    const rows = data
      .map((d) => ({ t: toUnixSeconds(d.ts) as Time, d }))
      .sort((a, b) => (a.t as number) - (b.t as number))
    candleRef.current.setData(
      rows.map((r) => ({
        time: r.t,
        open: r.d.o,
        high: r.d.h,
        low: r.d.l,
        close: r.d.c,
      })),
    )
    volRef.current.setData(
      rows.map((r) => ({
        time: r.t,
        value: r.d.v ?? 0,
        // 量柱半透明:token hex 拼 8 位 alpha(#RRGGBBAA,0.4≈66),对齐 prototype opacity 0.4 不抢主图。
        color: (r.d.c >= r.d.o ? cssVar('--up') : cssVar('--down')) + '66',
      })),
    )
    lastTimeRef.current = rows.length ? (rows[rows.length - 1]!.t as number) : undefined
  }, [data])

  // 增量更新(WS 最新 candle):update() 同 time replace 最后 / 新 time append,保留缩放/十字光标(不像 setData 全量重渲染)。
  useEffect(() => {
    if (!candleRef.current || !volRef.current || !updateCandle) return
    const t = toUnixSeconds(updateCandle.ts) as Time
    // 防 "Cannot update oldest data":series 空(data 未加载/快速切 interval 短暂空)或
    // time 倒退(旧 candle 残留/WS 时序乱)时 skip — 不调 update,避免 lightweight-charts 抛错崩组件。
    if (lastTimeRef.current == null || (t as number) < lastTimeRef.current) return
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
      // 量柱半透明(同 setData,0.4 alpha 不抢主图)。
      color: (updateCandle.c >= updateCandle.o ? cssVar('--up') : cssVar('--down')) + '66',
    })
    lastTimeRef.current = t as number
  }, [updateCandle])

  return <div ref={containerRef} className={className} style={{ height, width: '100%' }} />
}
