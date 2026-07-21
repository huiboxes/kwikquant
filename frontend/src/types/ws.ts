/**
 * WS 消息类型(镜像 docs/ws-contract.md §3 schema)。
 *
 * 后端推完整 Ticker/Kline record(见 market/domain/{Ticker,Kline}.java),Jackson 默认 camelCase 序列化。
 * ⚠ BigDecimal 字段实际序列化为 **number**(Jackson 默认 BigDecimal→JSON number,后端无全局
 * write-bigdecimal-as-plain 也无 @JsonFormat(shape=STRING)),**不是 string**。这是金额红线缺口
 * (BigDecimal 应 string 保精度),长期 TD 后端加 @JsonFormat(shape=STRING) 或全局 Jackson 配
 * BigDecimal→string,届时本类型改 string。现状:前端用 toDecimal(string|number) 兼容,不直接
 * number 运算(money.ts 入口)。
 *
 * 注:ws-contract.md §3.1/§3.2 字段表标 string 是历史漂移(实际 number),已加注 TD。
 */

/** WS 推送的 Ticker(/topic/ticker/{ex}/{mt}/{sym-dash},MarketDataService.onTicker 推整个 Ticker record)。 */
export interface WsTicker {
  /** 交易所枚举字符串:OKX | BINANCE | BITGET | PAPER */
  exchange: string
  /** 市场类型:SPOT | PERP(后端 MarketType 枚举,PERP=永续合约) */
  marketType: string
  /** canonical symbol,如 "BTC/USDT"(带斜杠;destination 路径段里才替成 BTC-USDT) */
  symbol: string
  /** 最新价(BigDecimal→number 序列化,前端走 toDecimal 不直接运算) */
  last: number
  /** 买一价 */
  bid: number
  /** 卖一价 */
  ask: number
  /** 24h 最高 */
  high: number
  /** 24h 最低 */
  low: number
  /** 24h 开盘 */
  open: number
  /** 24h 基础成交量 */
  baseVolume: number
  /** 24h 计价成交量 */
  quoteVolume: number
  /** 24h 涨跌额 */
  change: number
  /** 24h 涨跌幅(百分比) */
  percentage: number
  /** 行情时间 ISO-8601 UTC */
  timestamp: string
  /** 后端接收时间 ISO-8601 UTC */
  receivedAt: string
}

/**
 * 算 WS ticker 订阅 destination(对齐 MarketDataService.TICKER_TOPIC_FORMAT + symbol.replace("/", "-"))。
 * 例:("OKX","SPOT","BTC/USDT") → "/topic/ticker/OKX/SPOT/BTC-USDT"
 */
export function tickerDestination(exchange: string, marketType: string, symbol: string): string {
  return `/topic/ticker/${exchange}/${marketType}/${symbol.replace('/', '-')}`
}

/**
 * WS 推送的 Kline(/topic/kline/{ex}/{mt}/{sym-dash}/{interval.ccxtValue},MarketDataService.onKline 推整个 Kline record)。
 *
 * ⚠ open/high/low/close/volume 是 **number**(后端 Kline BigDecimal→double 序列化,非 string)——
 * 金额红线缺口(BigDecimal 应 string),长期 TD 后端 springdoc 配 BigDecimal→string + 前端 decimal.js。
 * 本 plan 不改(KlineChart 沿用 number,与 Kline 序列化现状一致)。
 * interval 是枚举名 "_1m"(Jackson name()),**不是** ccxtValue "1m" —— WS destination 段才用 ccxtValue。
 */
export interface WsKline {
  exchange: string
  marketType: string
  symbol: string // canonical "BTC/USDT"
  interval: string // 枚举名 "_1m"(非 ccxtValue)
  openTime: string // ISO-8601
  open: number
  high: number
  low: number
  close: number
  volume: number
}

/**
 * 算 WS kline 订阅 destination(对齐 MarketDataService.KLINE_TOPIC_FORMAT + interval.ccxtValue())。
 * 例:("OKX","SPOT","BTC/USDT","15m") → "/topic/kline/OKX/SPOT/BTC-USDT/15m"
 * interval 用 ccxtValue("1m"|"15m",无下划线),不是枚举名 "_1m"。
 */
export function klineDestination(
  exchange: string,
  marketType: string,
  symbol: string,
  intervalCcxt: string,
): string {
  return `/topic/kline/${exchange}/${marketType}/${symbol.replace('/', '-')}/${intervalCcxt}`
}

/**
 * WS 推送的强平事件(/topic/liquidations/{userId},LiquidationWebSocketBroadcaster @EventListener 推)。
 *
 * 后端 LiquidationEvent record(@shared/types/LiquidationEvent.java)字段:
 * userId/orderId/accountId/positionId/positionSide/leverage/liquidationPrice/
 * markPrice/marginBalance/realizedPnl/reason/timestamp。
 *
 * ⚠ BigDecimal 字段(leverage/liquidationPrice/markPrice/marginBalance/realizedPnl)实际序列化为
 * **number**(Jackson BigDecimal→JSON number,后端无全局 write-bigdecimal-as-plain),**不是 string**。
 * 这是金额红线缺口(BigDecimal 应 string 保精度),长期 TD 后端加 @JsonFormat(shape=STRING) 或
 * 全局 Jackson 配 BigDecimal→string,届时本类型改 string。现状:前端用 toDecimal(string|number)
 * 兼容(money.ts 入口),不直接 number 运算。详见 ws-contract.md §3.9。
 */
export interface WsLiquidation {
  /** 持仓所属用户 ID(订阅 destination 段) */
  userId: number
  /** 触发强平的订单 ID;系统强平(无 user 提交订单)为 null */
  orderId: number | null
  /** 账户 ID */
  accountId: number
  /** 被强平的持仓 ID */
  positionId: number
  /** 合约持仓方向:LONG | SHORT */
  positionSide: string
  /** 持仓杠杆倍数(BigDecimal→number,null=派生未算出) */
  leverage: number | null
  /** 强平价(BigDecimal→number,null=派生未算出) */
  liquidationPrice: number | null
  /** 触发时刻标记价(BigDecimal→number,null=派生未算出) */
  markPrice: number | null
  /** 触发时刻保证金余额 = frozenAmount + realizedPnl(BigDecimal→number) */
  marginBalance: number | null
  /** 强平后该持仓已实现盈亏(BigDecimal→number,USDT) */
  realizedPnl: number | null
  /** 触发原因文案(人类可读,非 i18n key) */
  reason: string
  /** 触发时间 ISO-8601 UTC */
  timestamp: string
}

/**
 * 算 WS 强平事件订阅 destination(对齐 LiquidationWebSocketBroadcaster 推的
 * `/topic/liquidations/{userId}`)。
 *
 * 用户级 fanout:同一用户多持仓可并发强平,destination 不按 positionId 拆,
 * positionId 放 body(前端按 positionId 区分)。
 *
 * 例:(42) → "/topic/liquidations/42"
 */
export function liquidationDestination(userId: number | string): string {
  return `/topic/liquidations/${userId}`
}
