import type { Command } from 'commander'
import { apiGet } from './client.js'
import { output, table } from './output.js'
import {
  globalOpts,
  marketOpts,
  fmt,
  fail,
  resolveCreds,
  symbolPath,
  normalizeMarket,
  type MarketOpts,
} from './shared.js'

/** 行情域:quote / kline / depth / pairs / tickers。 */
export function registerMarket(program: Command): void {
  // ============================================================
  // quote <symbols...> — 多 symbol(对标长桥 longbridge quote TSLA.US NVDA.US)
  // ============================================================
  globalOpts(marketOpts(program.command('quote <symbols...>')))
    .description('查最新价(默认 okx spot,支持多 symbol,例:BTC/USDT ETH/USDT)')
    .action(async (symbols: string[], opts: MarketOpts) => {
      try {
        const creds = resolveCreds(opts)
        const { exchange, marketType } = normalizeMarket(opts)
        const tickers = await Promise.all(
          symbols.map(async (sym) => {
            const data = await apiGet<unknown>(
              creds,
              `/api/v1/market/ticker/${exchange}/${marketType}/${symbolPath(sym)}`,
            )
            const resp = data as Record<string, unknown>
            const t = (resp.ticker ?? resp) as Record<string, unknown>
            return {
              symbol: sym,
              stale: resp.stale === true,
              last: t.last,
              bid: t.bid,
              ask: t.ask,
              baseVolume: t.baseVolume,
              quoteVolume: t.quoteVolume,
            }
          }),
        )
        output(tickers, fmt(opts), (d) =>
          table(
            ['交易对', '最新价', '买一', '卖一', '24h量'],
            d.map((t) => [
              t.symbol + (t.stale ? ' (stale)' : ''),
              String(t.last ?? '-'),
              String(t.bid ?? '-'),
              String(t.ask ?? '-'),
              String(t.baseVolume ?? t.quoteVolume ?? '-'),
            ]),
          ),
        )
      } catch (e) {
        fail(e)
      }
    })

  // ============================================================
  // kline <symbol> — 多周期(1m|5m|15m|1h|4h|1d),symbol 用 canonical BTC/USDT(RequestParam 解 %2F)
  // ============================================================
  globalOpts(
    marketOpts(program.command('kline <symbol>'))
      .description('查历史 K 线(默认 okx spot 1h 100 根)')
      .option('-p, --period <interval>', 'K 线周期 1m|5m|15m|1h|4h|1d', '1h')
      .option('--limit <n>', '返回条数 1-1000', '100')
      .option('--before <iso>', '往前加载(ISO-8601,如 2026-07-17T10:00:00Z)'),
  ).action(
    async (
      symbol: string,
      opts: MarketOpts & { period: string; limit: string; before?: string },
    ) => {
      try {
        const creds = resolveCreds(opts)
        const { exchange, marketType } = normalizeMarket(opts)
        const params = new URLSearchParams({
          exchange,
          marketType,
          symbol,
          interval: opts.period,
          limit: opts.limit,
        })
        if (opts.before) params.set('before', opts.before)
        const data = await apiGet<unknown[]>(creds, `/api/v1/market/klines?${params}`)
        output(data, fmt(opts), (d) =>
          table(
            ['时间', '开', '高', '低', '收', '量'],
            d.map((k) => {
              const v = k as Record<string, unknown>
              return [
                String(v.openTime ?? v.timestamp ?? '-'),
                String(v.open ?? '-'),
                String(v.high ?? '-'),
                String(v.low ?? '-'),
                String(v.close ?? '-'),
                String(v.volume ?? '-'),
              ]
            }),
          ),
        )
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // depth <symbol> — 盘口深度(symbol 用 - 替换 /,PathVariable)
  // OrderBook.bids/asks 是 List<PriceLevel>,PriceLevel(price, qty) 序列化为对象 {price, qty}(非数组)
  // ============================================================
  globalOpts(
    marketOpts(program.command('depth <symbol>'))
      .description('查盘口深度(默认 okx spot 20 档)')
      .option('-d, --depth <n>', '档数 1-100', '20'),
  ).action(async (symbol: string, opts: MarketOpts & { depth: string }) => {
    try {
      const creds = resolveCreds(opts)
      const { exchange, marketType } = normalizeMarket(opts)
      const data = await apiGet<unknown>(
        creds,
        `/api/v1/market/orderbook/${exchange}/${marketType}/${symbolPath(symbol)}?depth=${opts.depth}`,
      )
      output(data, fmt(opts), (d) => {
        const v = d as Record<string, unknown>
        const bids = (v.bids ?? []) as Array<Record<string, unknown>>
        const asks = (v.asks ?? []) as Array<Record<string, unknown>>
        const n = Math.max(bids.length, asks.length)
        if (n === 0) return '(空)'
        const rows = Array.from({ length: n }, (_, i) => [
          String(i + 1),
          String(bids[i]?.price ?? '-'),
          String(bids[i]?.qty ?? bids[i]?.amount ?? '-'),
          String(asks[i]?.price ?? '-'),
          String(asks[i]?.qty ?? asks[i]?.amount ?? '-'),
        ])
        return table(['档', '买价', '买量', '卖价', '卖量'], rows)
      })
    } catch (e) {
      fail(e)
    }
  })

  // ============================================================
  // pairs — 交易对列表
  // ============================================================
  globalOpts(
    marketOpts(program.command('pairs').description('查交易对列表(默认 okx spot)')),
  ).action(async (opts: MarketOpts) => {
    try {
      const creds = resolveCreds(opts)
      const { exchange, marketType } = normalizeMarket(opts)
      const data = await apiGet<unknown[]>(
        creds,
        `/api/v1/market/pairs?exchange=${exchange}&marketType=${marketType}`,
      )
      output(data, fmt(opts), (d) =>
        table(
          ['交易对', '基础币', '报价币'],
          d.map((p) => {
            const v = p as Record<string, unknown>
            return [
              String(v.symbol ?? '-'),
              String(v.base ?? v.baseAsset ?? '-'),
              String(v.quote ?? v.quoteAsset ?? '-'),
            ]
          }),
        ),
      )
    } catch (e) {
      fail(e)
    }
  })

  // ============================================================
  // tickers — 批量行情(可排序分页)
  // ============================================================
  globalOpts(
    marketOpts(program.command('tickers').description('批量查行情(默认 okx spot 按成交额降序前 200)'))
      .option('--sort <field>', '排序 quoteVolume|percentage|last', 'quoteVolume')
      .option('--order <dir>', '方向 desc|asc', 'desc')
      .option('--limit <n>', '返回数量 1-500', '200')
      .option('--search <kw>', '按 symbol like 过滤'),
  ).action(
    async (opts: MarketOpts & { sort: string; order: string; limit: string; search?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const { exchange, marketType } = normalizeMarket(opts)
        const params = new URLSearchParams({
          exchange,
          marketType,
          sort: opts.sort,
          order: opts.order,
          limit: opts.limit,
        })
        if (opts.search) params.set('search', opts.search)
        const data = await apiGet<unknown[]>(creds, `/api/v1/market/tickers?${params}`)
        output(data, fmt(opts), (d) =>
          table(
            ['交易对', '最新价', '涨跌幅', '24h量'],
            d.map((item) => {
              const r = item as Record<string, unknown>
              const t = (r.ticker ?? r) as Record<string, unknown>
              return [
                String(t.symbol ?? r.symbol ?? '-'),
                String(t.last ?? '-'),
                String(t.percentage ?? t.change ?? '-'),
                String(t.baseVolume ?? t.quoteVolume ?? '-'),
              ]
            }),
          ),
        )
      } catch (e) {
        fail(e)
      }
    },
  )
}
