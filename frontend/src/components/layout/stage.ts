/**
 * 策略工作台阶段(stage)— URL ?stage= 深链派生(spec §3.5 / §4.1)。
 *
 * 纯函数 deriveStage 便于单测;组件用 useSearchParams + deriveStage。
 * 批 1a:code 态;批 1b 加 backtest 态(canBacktest 由 publish 完成解锁)。
 */
export type Stage = 'code' | 'backtest'

export const STAGES: readonly Stage[] = ['code', 'backtest'] as const

export const DEFAULT_STAGE: Stage = 'code'

/**
 * 从 URLSearchParams 派生当前 stage。
 * 非法/缺失值回落 DEFAULT_STAGE('code')。
 */
export function deriveStage(params: URLSearchParams): Stage {
  const raw = params.get('stage')
  if (raw === 'code' || raw === 'backtest') return raw
  return DEFAULT_STAGE
}
