import { describe, it, expect, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { FlashNumber } from './FlashNumber'

describe('FlashNumber', () => {
  it('首帧直接渲染,无方向色', () => {
    render(<FlashNumber value="100,000.00" />)
    const el = screen.getByText('100,000.00')
    expect(el.className).not.toContain('kq-tick-up')
    expect(el.className).not.toContain('kq-tick-down')
  })

  it('值变大闪涨色,值变小闪跌色', () => {
    vi.useFakeTimers()
    const { rerender } = render(<FlashNumber value="100" />)
    const el = () => screen.getByText(/^10[01]$/)

    act(() => rerender(<FlashNumber value="101" />))
    expect(el().className).toContain('kq-tick-up')

    act(() => vi.advanceTimersByTime(900))
    expect(el().className).not.toContain('kq-tick-up')

    act(() => rerender(<FlashNumber value="100" />))
    expect(el().className).toContain('kq-tick-down')
    vi.useRealTimers()
  })

  it('非数字变化不闪', () => {
    const { rerender } = render(<FlashNumber value="--" />)
    act(() => rerender(<FlashNumber value="--" />))
    const el = screen.getByText('--')
    expect(el.className).not.toContain('kq-tick-up')
  })
})
