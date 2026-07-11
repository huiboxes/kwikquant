import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AppLayout } from './AppLayout'

describe('AppLayout', () => {
  it('渲染 sidebar(banner/complementary)+ main', () => {
    render(
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>,
    )
    expect(screen.getByRole('banner')).toBeInTheDocument() // TopBar <header>
    expect(screen.getByRole('complementary')).toBeInTheDocument() // SidebarRail <aside>
    expect(screen.getByRole('main')).toBeInTheDocument()
  })

  it('main 内层容器有 max-w-[1400px] mx-auto', () => {
    render(
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>,
    )
    const main = screen.getByRole('main')
    const inner = main.firstElementChild
    expect(inner?.className).toContain('max-w-[1400px]')
    expect(inner?.className).toContain('mx-auto')
  })

  it('根容器 flex h-screen + 暖画布底', () => {
    const { container } = render(
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>,
    )
    const root = container.firstElementChild
    expect(root?.className).toContain('flex')
    expect(root?.className).toContain('h-dvh')
    expect(root?.className).toContain('bg-surface-canvas')
  })
})
