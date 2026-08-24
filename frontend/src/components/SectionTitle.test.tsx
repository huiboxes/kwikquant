import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SectionTitle } from './SectionTitle'

describe('SectionTitle', () => {
  it('标题走 h3 字阶且锁 400(层级靠字号与颜色,不靠重量)', () => {
    render(<SectionTitle title="运行中策略" sub="0 个" />)
    const title = screen.getByText('运行中策略')
    expect(title.className).toContain('text-h3')
    expect(title.className).not.toContain('font-bold')
  })
})
