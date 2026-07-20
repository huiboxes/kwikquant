import { useCallback, useEffect, useMemo, useState } from 'react'
import { useKlines } from '@/hooks/useMarket'
import { fetchKlines, subscribeKlineMarket, unsubscribeKlineMarket } from '@/api/market'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { klineDestination, type WsKline } from '@/types/ws'
import type { KlineCandle } from '@/components/charts/KlineChart'

/**
 * useKlineChart — K 线完整逻辑(500 根首屏 + before 分页 + history dedup + WS 增量 + interval 切换校验)。
 *
 * 从 MarketPage 旧 K 线逻辑抽成共享 hook,TradingPage 用(替换原 stub:100 根/写死 15m/TD-047 留账)。
 * MarketPage Task C 重写为列表后不再有 K 线,本 hook 是 K 线逻辑唯一载体。
 *
 * 数据流:
 *  - useKlines(REST 500 根首屏)+ fetchKlines(before=earliest) 增量往前滚 → history 累积 prepend
 *  - candles = recent ∪ history dedup(recent 优先) + sort asc
 *  - useWsTopic(klineDestination) 收最新 candle → updateCandle 增量(保留缩放,lightweight-charts 增量 merge)
 *  - subscribeKlineMarket/unsubscribeKlineMarket 按需起后端 kline worker(idle 30s 退订)
 *
 * 切 symbol/interval/exchange → 清 history(新 symbol/interval 重新累积,不混旧)。
 * WS interval 校验:旧 interval 在途消息不 append 到新 series(WsKline.interval 枚举名比对)。
 */
export interface UseKlineChartParams {
  exchange: string
  marketType: string
  symbol: string
  interval: string
  limit?: number
}

export interface UseKlineChartResult {
  candles: KlineCandle[]
  updateCandle?: KlineCandle
  loadingMore: boolean
  onLoadMore: () => void
  isLoading: boolean
  error: Error | null
  refetch: () => void
}

export function useKlineChart(params: UseKlineChartParams): UseKlineChartResult {
  const { exchange, marketType, symbol, interval, limit = 500 } = params
  const klines = useKlines({ exchange, marketType, symbol, interval, limit })

  const recentCandles = useMemo<KlineCandle[]>(
    () =>
      (klines.data ?? []).map((k) => ({
        ts: k.openTime ?? '',
        o: k.open ?? 0,
        h: k.high ?? 0,
        l: k.low ?? 0,
        c: k.close ?? 0,
        v: k.volume ?? 0,
      })),
    [klines.data],
  )

  // 往前滚加载历史:history 累积更早 candle(prepend),与 recentCandles 合并去重 + sort asc。
  // before 严格 < earliest,history ts 与 recent ts 不重叠;dedup 取 recent(新)优先,safety。
  const [history, setHistory] = useState<KlineCandle[]>([])
  const [loadingMore, setLoadingMore] = useState(false)
  const candles = useMemo(() => {
    const byTs = new Map<string, KlineCandle>()
    for (const c of recentCandles) if (c.ts) byTs.set(c.ts, c) // recent 优先(新)
    for (const c of history) if (c.ts && !byTs.has(c.ts)) byTs.set(c.ts, c) // history 补(不覆盖 recent)
    return [...byTs.values()].sort((a, b) => a.ts.localeCompare(b.ts))
  }, [history, recentCandles])

  // 切 interval/symbol/exchange → 清 history(新 symbol/interval 重新累积,不混旧)。
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setHistory([])
  }, [interval, symbol, exchange])

  // 往前滚到最左(KlineChart onLoadMore 触发)→ 拉 before=earliest 的更早 260 根 → prepend history
  const onLoadMore = useCallback(() => {
    if (loadingMore || candles.length === 0) return
    const earliest = candles[0]!.ts
    setLoadingMore(true)
    fetchKlines({ exchange, marketType, symbol, interval, limit: 260, before: earliest })
      .then((older) => {
        const seen = new Set(history.map((c) => c.ts))
        const olderCandles: KlineCandle[] = older
          .map((k) => ({
            ts: k.openTime ?? '',
            o: k.open ?? 0,
            h: k.high ?? 0,
            l: k.low ?? 0,
            c: k.close ?? 0,
            v: k.volume ?? 0,
          }))
          .filter((c) => c.ts !== '' && !seen.has(c.ts))
        if (olderCandles.length > 0) setHistory((prev) => [...olderCandles, ...prev])
      })
      .catch(() => {})
      .finally(() => setLoadingMore(false))
  }, [loadingMore, candles, exchange, marketType, symbol, interval, history])

  // WS 实时 kline:订阅 /topic/kline/{ex}/{mt}/{sym-dash}/{ccxtInterval},收最新 candle → updateCandle 增量(保留缩放)
  const [updateCandle, setUpdateCandle] = useState<KlineCandle | undefined>()
  const ccxtInterval = interval.replace(/^_/, '')
  const klineDest = symbol ? klineDestination(exchange, marketType, symbol, ccxtInterval) : null
  useWsTopic(klineDest, (payload) => {
    const k = payload as WsKline
    // 校验 interval:旧 interval 在途消息/后端 unsubscribe 慢一拍不能 append 到新 series
    // (WsKline.interval 是枚举名 _1m,与 state interval(_15m)比对,非 ccxtInterval)
    if (!k?.openTime || k.interval !== interval) return
    setUpdateCandle({
      ts: k.openTime,
      o: k.open,
      h: k.high,
      l: k.low,
      c: k.close,
      v: k.volume,
    })
  })

  // 切 symbol/interval → POST /subscribe/kline 起后端 kline worker(按需,idle 30s 退订);unmount/切走 POST /unsubscribe/kline
  // 注:不需 setUpdateCandle(undefined) 重置 — useWsTopic interval 校验拦截旧 interval 消息,
  // 且 updateCandle effect 依赖未变不触发 update,旧 candle 不会 append 到新 data。
  useEffect(() => {
    if (!symbol) return
    void subscribeKlineMarket({ exchange, marketType, symbol, interval: ccxtInterval }).catch(() => {})
    return () => {
      void unsubscribeKlineMarket({ exchange, marketType, symbol, interval: ccxtInterval }).catch(() => {})
    }
  }, [exchange, marketType, symbol, ccxtInterval])

  return {
    candles,
    updateCandle,
    loadingMore,
    onLoadMore,
    isLoading: klines.isLoading,
    error: klines.error ? (klines.error as Error) : null,
    refetch: () => klines.refetch(),
  }
}
