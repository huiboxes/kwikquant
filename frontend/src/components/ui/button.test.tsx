import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Button } from './button'

describe('Button', () => {
  it('default 档是 pill 几何 + 40px 触达高度(契约 button-primary)', () => {
    render(<Button>提交</Button>)
    const btn = screen.getByRole('button', { name: '提交' })
    expect(btn.className).toContain('rounded-pill')
    expect(btn.className).toContain('h-10')
  })

  it('icon 档走 rounded-full(契约 icon plate 默认圆形)', () => {
    render(
      <Button size="icon" aria-label="关闭">
        ×
      </Button>,
    )
    const btn = screen.getByRole('button', { name: '关闭' })
    expect(btn.className).toContain('rounded-full')
  })

  it('primary 不带 glow 阴影(体系只保留 card/pop 两层)', () => {
    render(<Button>提交</Button>)
    const btn = screen.getByRole('button', { name: '提交' })
    expect(btn.className).not.toContain('shadow-glow')
  })

  it('focus 环 2px 实色(契约 a11y)', () => {
    render(<Button>提交</Button>)
    const btn = screen.getByRole('button', { name: '提交' })
    expect(btn.className).toContain('focus-visible:ring-2')
    expect(btn.className).not.toContain('ring-ring/50')
  })
})
