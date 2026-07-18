import type { BacktestSubmitRequest } from '@/api/backtest'

/**
 * parseImportReport — 解析外部回测报告 JSON 文本,前端轻量校验后交后端。
 *
 * 后端 `POST /reports/import` 接 `BacktestSubmitRequest`(name/params/symbol/timeframe/
 * period/trades/equityCurve,ws-contract + api-gen schema)。前端只做结构校验:
 * JSON 可解析 + 必填字段存在 + trades 非空 + equityCurve/params 类型正确。
 * 深层校验(字段语义/范围/金额正负)交给后端 400 + errorMessage,不重复实现。
 *
 * 不做格式转换:外部 JSON 必须就是 BacktestSubmitRequest 结构(契约即格式约定)。
 * 若用户的报告来自别的工具、格式不同,需先转换 —— 留用户决定是否加转换层。
 */
export type ParseImportResult =
  | { ok: true; data: BacktestSubmitRequest }
  | { ok: false; error: string }

const REQUIRED_KEYS = [
  'name',
  'params',
  'symbol',
  'timeframe',
  'period',
  'trades',
  'equityCurve',
] as const

export function parseImportReport(text: string): ParseImportResult {
  let json: unknown
  try {
    json = JSON.parse(text)
  } catch (e) {
    return { ok: false, error: `JSON 格式错误: ${(e as Error).message}` }
  }
  if (typeof json !== 'object' || json === null || Array.isArray(json)) {
    return { ok: false, error: '根必须是 JSON 对象' }
  }
  const obj = json as Record<string, unknown>
  for (const k of REQUIRED_KEYS) {
    if (!(k in obj)) return { ok: false, error: `缺少必填字段: ${k}` }
  }
  if (!Array.isArray(obj.trades) || obj.trades.length === 0) {
    return { ok: false, error: 'trades 不能为空(契约要求 1-10000 条)' }
  }
  if (!Array.isArray(obj.equityCurve)) {
    return { ok: false, error: 'equityCurve 必须是数组' }
  }
  if (typeof obj.params !== 'object' || obj.params === null || Array.isArray(obj.params)) {
    return { ok: false, error: 'params 必须是对象' }
  }
  return { ok: true, data: obj as unknown as BacktestSubmitRequest }
}
