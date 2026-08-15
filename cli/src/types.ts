/**
 * CLI 响应类型 — 从 OpenAPI `/v3/api-docs` 生成(`src/types/api-gen.ts`,Wave 3.2d)。
 *
 * 生成:`pnpm gen:types`(需后端运行,默认 http://localhost:8080/v3/api-docs;
 * `KWIKQUANT_API_DOCS` env 覆盖)。与前端 `frontend/src/types/api-gen.ts` 同源同 schema。
 *
 * 本文件从 api-gen 抽 CLI 用到的数据载荷类型(apiGet/apiPost 已解 ApiResponse envelope,
 * 返 data 本体),替代旧 `as Record<string, unknown>` 硬转——编译期约束字段访问。
 * api-gen schema 字段多为 optional(springdoc 默认),CLI 读取处按需 `?? '-'` 兜底。
 */
import type { components } from './types/api-gen.js'

export type Schemas = components['schemas']

// ── market ──
export type Ticker = Schemas['Ticker']
export type TickerResponse = Schemas['TickerResponse']
export type Kline = Schemas['Kline']
export type OrderBook = Schemas['OrderBook']
export type PriceLevel = Schemas['PriceLevel']
export type TradingPairInfo = Schemas['TradingPairInfo']

// ── trading ──
export type OrderDetailDto = Schemas['OrderDetailDto']
export type PageDtoOrderDetailDto = Schemas['PageDtoOrderDetailDto']
export type OrderSubmitResult = Schemas['OrderSubmitResult']
export type FillDto = Schemas['FillDto']
export type PositionDto = Schemas['PositionDto']

// ── portfolio ──
export type PortfolioSummary = Schemas['PortfolioSummary']
export type AccountSummary = Schemas['AccountSummary']
export type PortfolioPnl = Schemas['PortfolioPnl']
export type PositionPnl = Schemas['PositionPnl']
export type EquitySnapshot = Schemas['EquitySnapshot']

// ── risk ──
export type RiskPolicyDto = Schemas['RiskPolicyDto']
export type RiskDecisionDto = Schemas['RiskDecisionDto']
export type PageDtoRiskDecisionDto = Schemas['PageDtoRiskDecisionDto']

// ── strategy / backtest ──
export type StrategyDetailDto = Schemas['StrategyDetailDto']
export type BacktestTaskDto = Schemas['BacktestTaskDto']

// ── trade history ──
export type TradeHistoryDto = Schemas['TradeHistoryDto']
export type PageDtoTradeHistoryDto = Schemas['PageDtoTradeHistoryDto']
export type TradeHistoryStatsDto = Schemas['TradeHistoryStatsDto']

// ── account ──
export type ExchangeAccountView = Schemas['ExchangeAccountView']
export type BalanceSnapshot = Schemas['BalanceSnapshot']
export type CurrencyBalance = Schemas['CurrencyBalance']
