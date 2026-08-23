import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Stat } from './Stat'

describe('Stat', () => {
  it('label 走 label-caps 字阶(11px/600/0.05em 单一实现)', () => {
    render(<Stat label="累计盈亏" value="12.4" />)
    const label = screen.getByText('累计盈亏')
    expect(label.className).toContain('text-label-caps')
  })

  it('数值走 metric 字阶(24px/600),不越级加粗', () => {
    render(<Stat label="累计盈亏" value="12.4" />)
    // tween 终值渲染需要一帧;class 断言与帧无关
    const label = screen.getByText('累计盈亏')
    const value = label.nextElementSibling!
    expect(value.className).toContain('text-metric')
    expect(value.className).not.toContain('font-bold')
  })
})
