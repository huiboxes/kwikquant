import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { LandingPage } from './LandingPage'

describe('LandingPage', () => {
  it('整页无装饰性品牌橙(图标/标签/数字/圆点/字母墙一律中性)', () => {
    const { container } = render(
      <MemoryRouter>
        <LandingPage />
      </MemoryRouter>,
    )
    // 主 CTA 按钮/链接上的品牌橙是合法动作色,排除后再断言装饰场景零用橙
    const decorated = [
      ...container.querySelectorAll(
        '.text-accent, .bg-accent, .bg-accent-soft, .text-accent-deep, .text-accent-warm',
      ),
    ].filter((el) => !el.closest('a, button'))
    expect(decorated).toHaveLength(0)
  })

  it('段标题与正文保持可扫读层级', () => {
    render(
      <MemoryRouter>
        <LandingPage />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.getByText('开始验证')).toBeInTheDocument()
  })
})
