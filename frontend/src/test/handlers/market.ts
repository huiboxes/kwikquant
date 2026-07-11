import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * market MSW handlers。
 *
 * mock 数据照原型 AppContext tickers 适配 Ticker schema:
 *  - 6 个主流 USDT 对(BTC/ETH/SOL/BNB/XRP/DOGE),BINANCE SPOT
 *  - XRP 标 stale:true 测 STALE 徽章
 *  - klines 60 条,基于 symbol.last 用 sin 生成稳定走势(不用 Math.random,测试稳定)
 *
 * honest(TD-008~011 记 tech-debt):
 *  - 后端无"列表 ticker"端点,MarketPage hardcode MARKET_SYMBOLS 循环 GET /ticker
 *  - order book 后端无端点,MarketPage 硬编码 mock(handler 不建 orderbook)
 *  - subscribe/unsubscribe 返占位字符串,WS 推送管理推 marketStore 阶段4
 */
type Ticker = components['schemas']['Ticker']
type TradingPairInfo = components['schemas']['TradingPairInfo']
type Kline = components['schemas']['Kline']

export const MARKET_EXCHANGE = 'BINANCE'
export const MARKET_TYPE = 'SPOT'
export const MARKET_SYMBOLS = [
  'BTC/USDT',
  'ETH/USDT',
  'SOL/USDT',
  'BNB/USDT',
  'XRP/USDT',
  'DOGE/USDT',
] as const

const NOW = '2026-07-12T01:20:00Z'

/** mock tickers(symbol → ticker + stale)。 */
const TICKERS: Record<string, { ticker: Ticker; stale: boolean }> = {
  'BTC/USDT': {
    ticker: {
      exchange: 'BINANCE', marketType: 'SPOT', symbol: 'BTC/USDT',
      last: 62500, bid: 62499, ask: 62501, high: 63200, low: 60800, open: 61200,
      baseVolume: 12000, quoteVolume: 750000000, change: 1300, percentage: 2.13,
      timestamp: NOW, receivedAt: NOW,
    },
    stale: false,
  },
  'ETH/USDT': {
    ticker: {
      exchange: 'BINANCE', marketType: 'SPOT', symbol: 'ETH/USDT',
      last: 3142, bid: 3141.5, ask: 3142.5, high: 3198, low: 3080, open: 3168,
      baseVolume: 95000, quoteVolume: 298000000, change: -26, percentage: -0.84,
      timestamp: NOW, receivedAt: NOW,
    },
    stale: false,
  },
  'SOL/USDT': {
    ticker: {
      exchange: 'BINANCE', marketType: 'SPOT', symbol: 'SOL/USDT',
      last: 142.6, bid: 142.5, ask: 142.7, high: 148, low: 138, open: 137.8,
      baseVolume: 850000, quoteVolume: 121000000, change: 4.8, percentage: 3.45,
      timestamp: NOW, receivedAt: NOW,
    },
    stale: false,
  },
  'BNB/USDT': {
    ticker: {
      exchange: 'BINANCE', marketType: 'SPOT', symbol: 'BNB/USDT',
      last: 585, bid: 584.8, ask: 585.2, high: 592, low: 578, open: 578,
      baseVolume: 42000, quoteVolume: 24500000, change: 7, percentage: 1.2,
      timestamp: NOW, receivedAt: NOW,
    },
    stale: false,
  },
  'XRP/USDT': {
    ticker: {
      exchange: 'BINANCE', marketType: 'SPOT', symbol: 'XRP/USDT',
      last: 0.52, bid: 0.519, ask: 0.521, high: 0.55, low: 0.49, open: 0.528,
      baseVolume: 95000000, quoteVolume: 49400000, change: -0.008, percentage: -1.5,
      timestamp: '2026-07-12T01:18:00Z', receivedAt: NOW,
    },
    stale: true,
  },
  'DOGE/USDT': {
    ticker: {
      exchange: 'BINANCE', marketType: 'SPOT', symbol: 'DOGE/USDT',
      last: 0.13, bid: 0.129, ask: 0.131, high: 0.142, low: 0.122, open: 0.123,
      baseVolume: 880000000, quoteVolume: 114000000, change: 0.007, percentage: 5.6,
      timestamp: NOW, receivedAt: NOW,
    },
    stale: false,
  },
}

/** 生成 60 条 Kline(基于 base 用 sin 走势,稳定无随机)。 */
function genKlines(symbol: string, interval: string, base: number): Kline[] {
  return Array.from({ length: 60 }, (_, i) => {
    const o = base * (1 + Math.sin(i * 0.3) * 0.01)
    const h = o * 1.004
    const l = o * 0.996
    const c = l + (h - l) * (0.5 + Math.sin(i * 0.5) * 0.4)
    return {
      exchange: 'BINANCE',
      marketType: 'SPOT',
      symbol,
      interval: interval as Kline['interval'],
      openTime: `2026-07-12T00:${String(i).padStart(2, '0')}:00Z`,
      open: o,
      high: h,
      low: l,
      close: c,
      volume: 50 + i,
    } satisfies Kline
  })
}

export const marketHandlers = [
  // GET /market/ticker/{exchange}/{marketType}/{symbol} → TickerResponse{ticker, stale}
  http.get(
    '/api/v1/market/ticker/:exchange/:marketType/:symbol',
    ({ params }) => {
      const sym = (params.symbol as string).replace('-', '/')
      const t = TICKERS[sym]
      if (!t) {
        return HttpResponse.json(envelope(null, 7004, 'symbol 不存在'), {
          status: 404,
        })
      }
      return HttpResponse.json(envelope(t))
    },
  ),

  // GET /market/pairs → TradingPairInfo[](按 exchange/marketType)
  http.get('/api/v1/market/pairs', ({ request }) => {
    const url = new URL(request.url)
    const exchange = url.searchParams.get('exchange') ?? 'BINANCE'
    const marketType = url.searchParams.get('marketType') ?? 'SPOT'
    const pairs: TradingPairInfo[] = MARKET_SYMBOLS.map((s) => ({
      exchange: exchange as TradingPairInfo['exchange'],
      marketType: marketType as TradingPairInfo['marketType'],
      symbol: s,
      baseAsset: s.split('/')[0],
      quoteAsset: 'USDT',
      active: true,
    }))
    return HttpResponse.json(envelope(pairs))
  }),

  // GET /market/klines → Kline[](按 exchange/marketType/symbol/interval/limit)
  http.get('/api/v1/market/klines', ({ request }) => {
    const url = new URL(request.url)
    const symbol = (url.searchParams.get('symbol') ?? 'BTC-USDT').replace('-', '/')
    const interval = url.searchParams.get('interval') ?? '_15m'
    const limit = parseInt(url.searchParams.get('limit') ?? '60', 10)
    const base = TICKERS[symbol]?.ticker.last ?? 62500
    const all = genKlines(symbol, interval, base)
    return HttpResponse.json(envelope(all.slice(-limit)))
  }),

  // POST /market/subscribe → 占位(WS 推送管理推 marketStore 阶段4)
  http.post('/api/v1/market/subscribe', () =>
    HttpResponse.json(envelope('订阅已申请')),
  ),

  // POST /market/unsubscribe
  http.post('/api/v1/market/unsubscribe', () =>
    HttpResponse.json(envelope('退订已申请')),
  ),
]
