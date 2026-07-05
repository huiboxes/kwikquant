import { z } from 'zod'

/**
 * 回测提交 schema(spec §5 step 7 骨架,step 19 完善)。
 * 后端 BacktestSubmitRequest:{name, params{[key:string]:unknown}(≤100), symbol, timeframe, strategyId, startTime, endTime, initialCapital?}。
 *
 * step 19 补:parameters snake_case 子 schema({initial_capital, ...})匹配 BacktestExecutionGateway 实际消费。
 * 当前骨架校验 name/symbol/timeframe 必填 + params 是对象 ≤100 项。
 */
export const backtestSubmitSchema = z.object({
  name: z.string().min(1, '请输入报告名称').max(200, '报告名称最多 200 字符'),
  symbol: z.string().min(1, '请输入交易对'),
  timeframe: z.string().min(1, '请选择时间周期'),
  strategyId: z.number().int().positive(),
  // params 是 {[key:string]:unknown};≤100 项校验 step 19 用 superRefine 补(zod record 无 .max)
  params: z.record(z.string(), z.unknown()),
})

export type BacktestSubmitInput = z.infer<typeof backtestSubmitSchema>
