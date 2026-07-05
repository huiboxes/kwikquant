import { describe, it, expect } from 'vitest'
import { deriveStage, DEFAULT_STAGE, STAGES } from './stage'

describe('deriveStage (URL ?stage= 派生纯函数)', () => {
  it('code 态正确派生', () => {
    expect(deriveStage(new URLSearchParams('?stage=code'))).toBe('code')
  })

  it('backtest 态正确派生', () => {
    expect(deriveStage(new URLSearchParams('?stage=backtest'))).toBe('backtest')
  })

  it('缺失 stage 回落 DEFAULT_STAGE(code)', () => {
    expect(deriveStage(new URLSearchParams())).toBe(DEFAULT_STAGE)
    expect(DEFAULT_STAGE).toBe('code')
  })

  it('非法 stage 值回落 code', () => {
    expect(deriveStage(new URLSearchParams('?stage=invalid'))).toBe('code')
    expect(deriveStage(new URLSearchParams('?stage='))).toBe('code')
  })

  it('保留其他 query params 不影响 stage 派生', () => {
    const params = new URLSearchParams('?taskId=42&stage=backtest')
    expect(deriveStage(params)).toBe('backtest')
    expect(params.get('taskId')).toBe('42')
  })

  it('STAGES 顺序为 code → backtest', () => {
    expect(STAGES).toEqual(['code', 'backtest'])
  })
})
