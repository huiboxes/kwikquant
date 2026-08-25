import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AuthBrandBand } from './AuthBrandBand'

describe('AuthBrandBand', () => {
  it('masonry 走正常流布局，不再绝对定位盖住 hero', () => {
    const { container } = render(<AuthBrandBand />)
    const masonry = container.querySelector('[data-slot="auth-masonry"]')
    expect(masonry).not.toBeNull()
    expect(masonry!.className).not.toContain('absolute')
  })

  it('品牌名只出现一次(pin 不再各带一个脚标)', () => {
    render(<AuthBrandBand />)
    expect(screen.getAllByText('KwikQuant')).toHaveLength(1)
  })

  it('hero 主张与四个能力 chip 正常渲染', () => {
    render(<AuthBrandBand />)
    expect(screen.getByText(/写策略，做回测/)).toBeInTheDocument()
    for (const label of ['连接交易所', '模拟盘验证', '代码版本管理', '下单前风控']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
  })
})
