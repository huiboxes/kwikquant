import { z } from 'zod'

/**
 * 回测提交 schema(spec §4.4 / §5 step 19,重写 step 7 骨架)。
 *
 * 后端 SubmitBacktestRequest(api-gen.ts:2139):
 *   { strategyId, symbol, exchange, intervalValue, startTime, endTime, parameters(JSON 字符串) }
 *
 * parameters 键名 snake_case(契约 G2 + spec §4.5):
 *   BacktestExecutionGateway + kwikquant_worker 实际消费 snake_case(initial_capital 等),
 *   OpenAPI example 已改 snake_case(见 api-gen.ts:2183 example)。
 *   前端 zod 校验 parameters 对象(snake_case 键),校验通过后 JSON.stringify 发送。
 *
 * 金额红线:initial_capital 是金额,schema 这层只校验是正数(z.number());
 *   组件用 RHF setValueAs 走 Decimal(str).toNumber() 转换(避免 Number()/parseFloat,
 *   ESLint 硬拦;Decimal 保证金额解析精度),提交时 JSON.stringify 发送 number。
 */

/** parameters 子 schema(snake_case 键名,匹配后端实际消费)。 */
export const backtestParametersSchema = z.object({
  /** 起始资金(USDT)。snake_case 键名匹配 BacktestExecutionGateway.java:118。 */
  initial_capital: z.number().positive('起始资金必须为正'),
})

export type BacktestParameters = z.infer<typeof backtestParametersSchema>

export const backtestSubmitSchema = z
  .object({
    strategyId: z.number().int().positive('策略 ID 必须为正'),
    symbol: z.string().min(1, '请输入交易对'),
    exchange: z.string().min(1, '请选择交易所'),
    intervalValue: z.string().min(1, '请选择 K 线周期'),
    startTime: z.string().min(1, '请选择起始时间'),
    endTime: z.string().min(1, '请选择结束时间'),
    parameters: backtestParametersSchema,
  })
  .refine((d) => new Date(d.startTime).getTime() < new Date(d.endTime).getTime(), {
    message: '起始时间必须早于结束时间',
    path: ['endTime'],
  })

export type BacktestSubmitInput = z.infer<typeof backtestSubmitSchema>
