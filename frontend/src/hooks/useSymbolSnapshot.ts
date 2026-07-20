import { useEffect, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchTicker, subscribeMarket, unsubscribeMarket } from '@/api/market'
import { marketKeys } from '@/api/_queryKeys'
import { useMarketStore } from '@/stores/marketStore'
import type { components } from '@/types/api-gen'

type Ticker = components['schemas']['Ticker']
type SubscribeRequest = components['schemas']['SubscribeRequest']

/**
 * useSymbolSnapshot — 单标的实时快照(REST 首拉 + WS tick 聚合)。
 *
 * 聚合策略(块 A 数据流统一,替原 useTicker + 独立 WS 订阅 + STALE 标签):
 *  - REST GET /market/ticker/{ex}/{mt}/{sym} → TickerResponse{ticker, stale}。首拉拿完整快照 + WS
 *    未连/未推时兜底。staleTime 30s 避免频繁重拉(实时性押 WS,不靠 REST 轮询)。
 *  - WS /topic/ticker/{ex}/{mt}/{sym}:后端推整个 Ticker record(全字段快照,非增量 patch),
 *    marketStore.ticks[symbol] 缓存最新一条。
 *  - 合并:snapshot = { ...restTicker, ...wsTick }。WsTicker 与 Ticker 字段 1:1 同名(REST 全 optional、
 *    WS 全 required),WS 覆盖 REST 同名,WS 未推/断连时 REST 值保留。WS 推全量 record 无需 diff/patch。
 *  - isRealtime = !!wsTick(marketStore 有此 symbol tick = WS 实时;无 = REST 快照)。不再用 REST .stale
 *    字段(那是"这次 REST 走 CCXT fallback"实现细节,useTicker 无 refetchInterval 永不刷新,挂 UI 误导)。
 *    连接状态归 TopBar 全局 WsConnectionIndicator。
 *
 * 订阅生命周期(吸收原 TradingPage 非 persistent sel 的 WS 订阅逻辑):
 *  - persistent symbol(传 persistentSymbols 判):复用页面级 subscribeTickers 已订,marketStore
 *    subscribedSymbols Set 守卫 no-op,不起后端 worker。
 *  - 非 persistent sel:POST /subscribe 起后端 worker + marketStore.subscribeTicker 订 destination 收 WS;
 *    切走/卸载 → unsub + POST /unsubscribe。idle 30s 退订兜底(后端管),前端静默不提示。
 *
 * persistent 批量预热订阅(8 symbol)仍是页面级 subscribeTickers 职责,hook 只管 sel 单 symbol。
 *
 * 金额红线:snapshot 字段 number(后端 BigDecimal→number 序列化,见 types/ws.ts TD),消费方走 toDecimal
 * 不直接运算。timestamp/receivedAt ISO string 透传。
 */
export function useSymbolSnapshot(
  exchange: string,
  marketType: string,
  symbol: string | undefined,
  persistentSymbols: readonly string[] = [],
) {
  const rest = useQuery({
    queryKey: marketKeys.ticker(exchange, marketType, symbol ?? ''),
    queryFn: () => fetchTicker(exchange, marketType, symbol as string),
    enabled: !!symbol,
    staleTime: 30_000,
  })

  const wsTick = useMarketStore((s) => (symbol ? s.ticks[symbol] : undefined))

  const data = useMemo<Ticker | undefined>(() => {
    const r = rest.data?.ticker
    if (!r) return wsTick ? ({ ...wsTick } as Ticker) : undefined
    // wsTick.exchange/marketType 是 string(Types/ws.ts),Ticker 是 enum(api-gen),运行时值一致,spread 后 cast 回 Ticker
    return { ...r, ...(wsTick ?? {}) } as Ticker
  }, [rest.data, wsTick])

  // 非 persistent sel 起后端 WS worker(persistent 已订 no-op,marketStore.subscribeTicker Set 守卫)
  useEffect(() => {
    if (!symbol) return
    if ((persistentSymbols as readonly string[]).includes(symbol)) return
    const body = { exchange, marketType, symbol } as SubscribeRequest
    void subscribeMarket(body).catch(() => {})
    const unsub = useMarketStore.getState().subscribeTicker(exchange, marketType, symbol)
    return () => {
      unsub()
      void unsubscribeMarket(body).catch(() => {})
    }
  }, [symbol, exchange, marketType, persistentSymbols])

  return {
    data,
    isLoading: rest.isLoading && !wsTick,
    isRealtime: !!wsTick,
    refetch: rest.refetch,
  }
}
