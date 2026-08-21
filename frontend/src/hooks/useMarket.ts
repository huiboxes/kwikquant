import { useQuery } from '@tanstack/react-query'
import { fetchPairs, fetchKlines, fetchOrderBook, fetchTickers, type KlinesQuery } from '@/api/market'
import { marketKeys } from '@/api/_queryKeys'

/** usePairs — 交易对列表(按交易所+市场类型)。 */
export function usePairs(exchange: string, marketType: string) {
  return useQuery({
    queryKey: marketKeys.pairs(exchange, marketType),
    queryFn: () => fetchPairs(exchange, marketType),
  })
}

/**
 * useOrderBook — 盘口深度(REST 轮询，后端无 orderbook WS，只有 ticker/kline WS)。
 * refetchInterval 3s 折中实时性 vs 请求成本；depth 默认 20(后端契约)。
 * TradingPage/MarketPage 共用真实端点。
 */
export function useOrderBook(
  exchange: string,
  marketType: string,
  symbol: string | undefined,
  depth?: number,
) {
  return useQuery({
    queryKey: marketKeys.orderbook(exchange, marketType, symbol ?? '', depth),
    queryFn: () => fetchOrderBook(exchange, marketType, symbol as string, depth),
    enabled: !!symbol,
    refetchInterval: 3000,
  })
}

/** useKlines — 历史 K 线(symbol/interval 变化重取)。 */
export function useKlines(q: KlinesQuery) {
  return useQuery({
    queryKey: marketKeys.klines(q),
    queryFn: () => fetchKlines(q),
    enabled: !!q.symbol,
  })
}

/**
 * useTradableSymbols — 策略页标的选择用：调 /market/tickers 按 24h 成交额降序，
 * 返 {symbol, quoteVolume}[](后端已 sort=quoteVolume&order=desc，前端不再排序)。
 *
 * 替代策略页原 usePairs(/market/pairs 无 volume 无序)。staleTime 60s 摊薄
 * loadTickers 延迟(OKX 1-2s)。空 symbol 过滤(防御 ticker.symbol 缺失)。
 * usePairs 保留给下单/校验需要 minQty 等静态信息的场景。
 */
export function useTradableSymbols(exchange: string, marketType: string) {
  return useQuery({
    queryKey: marketKeys.tickers(exchange, marketType),
    queryFn: async () => {
      const resp = await fetchTickers({ exchange, marketType, sort: 'quoteVolume', order: 'desc' })
      return resp
        .map((r) => ({ symbol: r.ticker.symbol ?? '', quoteVolume: r.ticker.quoteVolume ?? 0 }))
        .filter((s) => s.symbol !== '')
    },
    staleTime: 60_000,
  })
}

// subscribe/unsubscribe 走 WS 驱动(WS SUBSCRIBE/UNSUBSCRIBE，见 useSymbolSnapshot)。
