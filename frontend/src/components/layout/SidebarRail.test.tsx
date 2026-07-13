import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SidebarRail } from './SidebarRail'
import { useUiStore } from '@/stores/uiStore'

function renderWith(ui: React.ReactNode, initialEntry = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } })
  return render(<QueryClientProvider client={qc}><MemoryRouter initialEntries={[initialEntry]}>{ui}</MemoryRouter></QueryClientProvider>)
}

describe('SidebarRail', () => {
  it('渲染品牌 + 全部 nav 标签(展开态)', () => {
    renderWith(<SidebarRail />, '/')
    expect(screen.getByText('KwikQuant')).toBeInTheDocument()
    // '交易' label span 含 PAPER badge,单独测;其余 label 精确匹配
    for (const label of ['主页', '策略工作台', '回测', '组合总览', '行情', '风控', '交易历史', '设置']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
  })

  it('当前路径 / 时主页项 active(kq-nav-item active 类)', () => {
    renderWith(<SidebarRail />, '/')
    const homeLink = screen.getByText('主页').closest('a')!
    expect(homeLink.className).toContain('active')
  })

  it('trade 项显示 PAPER(uiStore 默认 PAPER)', () => {
    useUiStore.setState({ tradeMode: 'PAPER' })
    renderWith(<SidebarRail />, '/trade')
    expect(screen.getByText('PAPER')).toBeInTheDocument()
  })

  it('uiStore tradeMode=LIVE 时 trade 项显示 LIVE badge', () => {
    useUiStore.setState({ tradeMode: 'LIVE' })
    renderWith(<SidebarRail />, '/trade')
    expect(screen.getByText('LIVE')).toBeInTheDocument()
  })
})
