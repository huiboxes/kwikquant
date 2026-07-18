import { describe, it, expect } from 'vitest'
import { parseContentDispositionFilename } from '@/pages/history/parseContentDisposition'

describe('parseContentDispositionFilename', () => {
  it('带引号文件名', () => {
    expect(
      parseContentDispositionFilename('attachment; filename="trade-history.csv"'),
    ).toBe('trade-history.csv')
  })

  it('无引号文件名', () => {
    expect(parseContentDispositionFilename('attachment; filename=report.csv')).toBe(
      'report.csv',
    )
  })

  it('null 返回 null', () => {
    expect(parseContentDispositionFilename(null)).toBeNull()
  })

  it('空字符串返回 null', () => {
    expect(parseContentDispositionFilename('')).toBeNull()
  })

  it('无 filename 字段返回 null', () => {
    expect(parseContentDispositionFilename('attachment')).toBeNull()
  })

  it('中文文件名带引号', () => {
    expect(
      parseContentDispositionFilename('attachment; filename="交易历史.csv"'),
    ).toBe('交易历史.csv')
  })
})
