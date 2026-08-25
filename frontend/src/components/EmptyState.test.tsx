import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { EmptyState } from './EmptyState'

describe('EmptyState', () => {
  it('默认档：页面级空态，保留大留白', () => {
    render(<EmptyState title="还没有策略" />)
    expect(screen.getByRole('status').className).toContain('min-h-[240px]')
  })

  it('compact 档：表格内空态收紧，不撑大白板', () => {
    render(<EmptyState title="无持仓" compact />)
    const el = screen.getByRole('status')
    expect(el.className).toContain('min-h-[120px]')
    expect(el.className).not.toContain('min-h-[240px]')
  })
})
