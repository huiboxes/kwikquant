import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppLayout } from './AppLayout'

function renderWith(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } })
  return render(<QueryClientProvider client={qc}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>)
}

describe('AppLayout', () => {
  it('渲染 sidebar(banner/complementary)+ main', () => {
    renderWith(<AppLayout />)
    expect(screen.getByRole('banner')).toBeInTheDocument() // TopBar <header>
    expect(screen.getByRole('complementary')).toBeInTheDocument() // SidebarRail <aside>
    expect(screen.getByRole('main')).toBeInTheDocument()
  })

  it('main 内层容器有 max-w-[1400px] mx-auto', () => {
    renderWith(<AppLayout />)
    const main = screen.getByRole('main')
    const inner = main.firstElementChild
    expect(inner?.className).toContain('max-w-[1400px]')
    expect(inner?.className).toContain('mx-auto')
  })

  it('根容器 flex h-screen + 暖画布底', () => {
    const { container } = renderWith(<AppLayout />)
    const root = container.firstElementChild
    expect(root?.className).toContain('flex')
    expect(root?.className).toContain('h-dvh')
    expect(root?.className).toContain('bg-surface-canvas')
  })
})
