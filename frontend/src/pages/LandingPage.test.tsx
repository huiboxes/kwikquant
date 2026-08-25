import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { LandingPage } from './LandingPage'

describe('LandingPage', () => {
  it('客户端字母墙走品牌软底，不实心橙平铺', () => {
    render(
      <MemoryRouter>
        <LandingPage />
      </MemoryRouter>,
    )
    const letter = screen.getByText('Claude Code').parentElement!.querySelector('span')!
    expect(letter.className).toContain('bg-accent-soft')
    // 精确匹配裸的 bg-accent(bg-accent-soft 不算)
    expect(letter.className).not.toMatch(/(^|\s)bg-accent(\s|$)/)
  })
})
