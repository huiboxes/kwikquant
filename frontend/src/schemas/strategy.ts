import { z } from 'zod'

/**
 * 策略创建/编辑 schema(spec §5 step 7)。
 * 后端 CreateStrategyRequest:{name(≤200), description(≤2000), symbol, exchange, marketType, intervalValue, params(JSON 字符串)}。
 *
 * 枚举来源:Exchange enum(BINANCE|OKX|BYBIT|PAPER)+ marketType(SPOT|FUTURES)+ Interval(1m|5m|15m|30m|1h|4h|1d)。
 * params 是 JSON 字符串(策略编辑器产出),前端只校验非空字符串,具体内容由编辑器保证。
 */
export const EXCHANGES = ['BINANCE', 'OKX', 'BYBIT', 'PAPER'] as const
export const MARKET_TYPES = ['SPOT', 'FUTURES'] as const
export const INTERVALS = ['1m', '5m', '15m', '30m', '1h', '4h', '1d'] as const

export const createStrategySchema = z.object({
  name: z
    .string()
    .min(1, '请输入策略名称')
    .max(200, '策略名称最多 200 字符'),
  description: z
    .string()
    .max(2000, '策略描述最多 2000 字符')
    .optional()
    .default(''),
  symbol: z.string().min(1, '请输入交易对'),
  exchange: z.enum(EXCHANGES),
  marketType: z.enum(MARKET_TYPES),
  intervalValue: z.enum(INTERVALS),
  params: z.string().optional().default('{}'),
})

export type CreateStrategyInput = z.infer<typeof createStrategySchema>

/** 编辑策略(全量 PUT,字段同创建,id 从 URL 取不进 body) */
export const updateStrategySchema = createStrategySchema
export type UpdateStrategyInput = z.infer<typeof updateStrategySchema>
