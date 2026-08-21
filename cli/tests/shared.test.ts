import assert from 'node:assert/strict'
import { test } from 'node:test'
import { derivePositionEffect } from '../src/shared.js'

/** PERP positionEffect 自动派生(后端 Order 强制必填,CLI 省略时按 side 派生开仓方向)。 */
test('derivePositionEffect: buy → OPEN_LONG', () => {
  assert.equal(derivePositionEffect('buy'), 'OPEN_LONG')
})

test('derivePositionEffect: sell → OPEN_SHORT', () => {
  assert.equal(derivePositionEffect('sell'), 'OPEN_SHORT')
})

test('derivePositionEffect: 大小写不敏感', () => {
  assert.equal(derivePositionEffect('BUY'), 'OPEN_LONG')
  assert.equal(derivePositionEffect('Sell'), 'OPEN_SHORT')
})
