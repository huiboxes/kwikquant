import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Chip } from './Chip'

describe('Chip', () => {
  it('渲染 label', () => {
    render(<Chip label="BTC/USDT" />)
    expect(screen.getByText('BTC/USDT')).toBeDefined()
  })

  it.each([
    ['up', 'text-up'],
    ['down', 'text-down'],
    ['warning', 'text-warning-text'],
    ['info', 'text-info'],
    ['neutral', 'text-text-secondary'],
    ['accent', 'text-accent'],
  ] as const)('color=%s 应用 %s 类', (color, expected) => {
    render(<Chip label="x" color={color} />)
    expect(screen.getByText('x').className).toContain(expected)
  })

  it('size sm → text-caption', () => {
    render(<Chip label="x" size="sm" />)
    expect(screen.getByText('x').className).toContain('text-caption')
  })

  it('size md → text-body-sm', () => {
    render(<Chip label="x" size="md" />)
    expect(screen.getByText('x').className).toContain('text-body-sm')
  })

  it('无 onClose 不渲染关闭按钮', () => {
    render(<Chip label="x" />)
    expect(screen.queryByLabelText(/移除/)).toBeNull()
  })

  it('onClose 渲染按钮 + 点击触发', () => {
    const onClose = vi.fn()
    render(<Chip label="BTC" onClose={onClose} />)
    fireEvent.click(screen.getByLabelText(/移除 BTC/))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('默认 color=neutral size=sm', () => {
    render(<Chip label="x" />)
    const el = screen.getByText('x')
    expect(el.className).toContain('text-text-secondary')
    expect(el.className).toContain('text-caption')
  })
})
