import { type UTCTimestamp } from 'lightweight-charts'
import Decimal from 'decimal.js'
import { toUnixSeconds } from './toUnixSeconds'
import type { components } from '@/types/api-gen'

type EquityPointDto = components['schemas']['EquityPointDto']

export interface EquityLinePoint {
  time: UTCTimestamp
  value: number
}

/**
 * EquityPointDto[] → lightweight-charts line series 数据(纯函数,spec §5 step 21)。
 *
 * time:Instant ISO-8601 字符串 → UTCTimestamp(秒数);lightweight-charts v5 拒收 ISO 字符串。
 * equity:BigDecimal(后端序列化 number,api-gen.ts 标 number;运行时若发 string,Decimal 兼容)
 *        → number(Decimal 转换,金额红线;不用 Number()/parseFloat)。
 * 按 time 升序排(lightweight-charts 要求 line series 数据时间递增)。
 *
 * 单测 fixture(spec §5 step 21):
 *   {time:'2026-06-15T08:30:00Z', equity:10532.18} → {time:1750254600, value:10532.18}
 */
export function mapEquityCurve(points: EquityPointDto[]): EquityLinePoint[] {
  return points
    .map((p): EquityLinePoint | null => {
      try {
        const d = new Decimal(p.equity)
        if (d.isNaN()) return null
        return {
          time: toUnixSeconds(p.time) as UTCTimestamp,
          value: d.toNumber(),
        }
      } catch {
        // 非法 equity/time → 跳过该点(不崩图)
        return null
      }
    })
    .filter((p): p is EquityLinePoint => p !== null)
    .sort((a, b) => (a.time as number) - (b.time as number))
}
