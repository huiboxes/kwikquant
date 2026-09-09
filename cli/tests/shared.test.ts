import assert from 'node:assert/strict'
import { test } from 'node:test'
import { derivePositionEffect, sideFromPositionEffect } from '../src/shared.js'

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

/** side 单源派生表(对齐后端 PositionEffect.toSide):客户端四象限预检的正确性核心,
 *  写反即"拒合法、放非法"——逐项钉死。 */
test('sideFromPositionEffect: 四象限逐项对齐后端派生表', () => {
  assert.equal(sideFromPositionEffect('OPEN_LONG'), 'buy')
  assert.equal(sideFromPositionEffect('OPEN_SHORT'), 'sell')
  assert.equal(sideFromPositionEffect('CLOSE_LONG'), 'sell') // 平多=卖出多头持仓
  assert.equal(sideFromPositionEffect('CLOSE_SHORT'), 'buy') // 平空=买回空头持仓
})

test('sideFromPositionEffect: 大小写不敏感', () => {
  assert.equal(sideFromPositionEffect('open_long'), 'buy')
  assert.equal(sideFromPositionEffect('Close_Short'), 'buy')
})

test('sideFromPositionEffect 与 derivePositionEffect 开仓象限互逆', () => {
  // buy → OPEN_LONG → buy;sell → OPEN_SHORT → sell(客户端派生环闭合)
  assert.equal(sideFromPositionEffect(derivePositionEffect('buy')), 'buy')
  assert.equal(sideFromPositionEffect(derivePositionEffect('sell')), 'sell')
})
