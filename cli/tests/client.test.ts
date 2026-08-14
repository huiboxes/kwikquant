import assert from 'node:assert/strict'
import { test } from 'node:test'
import { assertEnvelope, ApiError, type ApiResponse } from '../src/client.js'

/** assertEnvelope:HTTP 2xx 但 code≠0(风控拒单 4105 等)必须抛,否则 CLI 误显"下单成功"。 */
test('assertEnvelope: code=0 returns data', () => {
  const env: ApiResponse<string> = { code: 0, message: 'ok', data: 'ok-data' }
  assert.equal(assertEnvelope(env, 200), 'ok-data')
})

test('assertEnvelope: HTTP 200 + code≠0 throws ApiError(风控拒单 4105)', () => {
  const env: ApiResponse<null> = { code: 4105, message: 'max notional exceeded', data: null }
  assert.throws(
    () => assertEnvelope(env, 200),
    (e: unknown) => e instanceof ApiError && (e as ApiError).code === 4105,
  )
})

test('assertEnvelope: null parsed throws empty-response error', () => {
  assert.throws(() => assertEnvelope(null, 200), (e: unknown) => e instanceof ApiError)
})

test('assertEnvelope: code=3001 validation error throws', () => {
  const env: ApiResponse<null> = { code: 3001, message: 'validation failed', data: null }
  assert.throws(
    () => assertEnvelope(env, 200),
    (e: unknown) => e instanceof ApiError && (e as ApiError).code === 3001,
  )
})
