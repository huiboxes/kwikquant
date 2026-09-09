import { describe, expect, it } from 'vitest'
import { EFFECT_LABELS, effectLabel } from './positionEffect'

/**
 * 四向意图 → 中文文案映射单测:short 侧写反(开空/平空)会整列染色+文案双错,
 * 此前无测试文件(locked by nothing)。long 侧 = up 语义色、short 侧 = down。
 */
describe('positionEffect 映射', () => {
  it('四向逐项:文案与 long/short 语义色归属', () => {
    expect(EFFECT_LABELS.OPEN_LONG).toEqual({ text: '开多', long: true })
    expect(EFFECT_LABELS.CLOSE_LONG).toEqual({ text: '平多', long: true })
    expect(EFFECT_LABELS.OPEN_SHORT).toEqual({ text: '开空', long: false })
    expect(EFFECT_LABELS.CLOSE_SHORT).toEqual({ text: '平空', long: false })
  })

  it('effectLabel:null/未知回退不吞数据', () => {
    expect(effectLabel(null)).toBe('—')
    expect(effectLabel(undefined)).toBe('—')
    expect(effectLabel('FUTURE_EFFECT')).toBe('FUTURE_EFFECT')
    expect(effectLabel('OPEN_LONG')).toBe('开多')
  })
})
