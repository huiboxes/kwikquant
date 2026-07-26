import { test, expect } from 'vitest'
import { formatRuleValue, RULE_PARAM_KEY, RULE_DESCRIPTION } from './risk'

test('MAX_INITIAL_MARGIN 比例展示百分比(0.8 → 80%)', () => {
  expect(formatRuleValue('MAX_INITIAL_MARGIN', { maxInitialMarginRatio: '0.8' })).toBe('80%')
  expect(formatRuleValue('MAX_INITIAL_MARGIN', { maxInitialMarginRatio: '1' })).toBe('100%')
  expect(formatRuleValue('MAX_INITIAL_MARGIN', {})).toBe('—')
})

test('RULE_PARAM_KEY/DESCRIPTION 补 MAX_INITIAL_MARGIN', () => {
  expect(RULE_PARAM_KEY.MAX_INITIAL_MARGIN).toBe('maxInitialMarginRatio')
  expect(RULE_DESCRIPTION.MAX_INITIAL_MARGIN).toContain('PERP')
})
