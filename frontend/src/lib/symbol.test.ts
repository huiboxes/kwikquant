import { describe, it, expect } from 'vitest'
import { stripContractSuffix } from './symbol'

describe('stripContractSuffix', () => {
  it('strips :USDT suffix from perp canonical', () => {
    expect(stripContractSuffix('BTC/USDT:USDT')).toBe('BTC/USDT')
  })
  it('leaves spot symbol unchanged', () => {
    expect(stripContractSuffix('BTC/USDT')).toBe('BTC/USDT')
  })
  it('does not strip non-USDT quote suffix', () => {
    expect(stripContractSuffix('BTC/BUSD')).toBe('BTC/BUSD')
  })
  it('returns empty string for null/undefined/empty', () => {
    expect(stripContractSuffix(null)).toBe('')
    expect(stripContractSuffix(undefined)).toBe('')
    expect(stripContractSuffix('')).toBe('')
  })
})
